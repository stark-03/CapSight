"""
Offline text-to-speech, on a single dedicated thread.

Three hard-won constraints shape this file:

1. On Windows, SAPI5 is a COM component. Any thread that drives it must call
   CoInitialize() first, or it will accept text and produce no sound at all -
   no exception, no warning, just silence. Since speech runs on its own thread,
   the initialisation has to happen INSIDE that thread. This is why Windows gets
   a direct SAPI5 backend rather than going through pyttsx3.

2. Speech engines die quietly. pyttsx3 in particular has a habit of speaking the
   first phrase and then no-oping forever, with runAndWait() returning instantly.
   The captions keep updating, so the system looks healthy while the user - who
   cannot see the screen - is told nothing. That is the worst failure this
   project can have, so a silent engine is detected by timing and restarted.

3. A queue is the WRONG structure for navigation guidance. If inference stalls
   and five instructions pile up, the user must hear the newest one - not sit
   through four stale commands while walking into a chair. So there is a single
   pending slot, and a new message overwrites it unless the pending one is more
   urgent.

Priority: 0 is most urgent.
"""
from __future__ import annotations

import platform
import threading
import time

IS_WINDOWS = platform.system() == "Windows"


# =========================================================== speech backends
class Sapi5Backend:
    """Windows speech, driven directly through SAPI5.

    Preferred over pyttsx3 on Windows because pyttsx3 wraps this same interface
    but adds an event loop that is the source of the "speaks once then silent"
    failure. Talking to SAPI5 directly removes that whole layer.
    """

    def __init__(self, rate: int, volume: float):
        import pythoncom
        import win32com.client

        # MUST happen on the thread that will do the speaking.
        pythoncom.CoInitialize()
        self._pythoncom = pythoncom
        self._voice = win32com.client.Dispatch("SAPI.SpVoice")
        # pyttsx3 speaks in words per minute (~175 is normal); SAPI5 uses a
        # -10..10 scale where 0 is normal. Map one onto the other.
        self._voice.Rate = max(-10, min(10, int(round((rate - 175) / 25.0))))
        self._voice.Volume = max(0, min(100, int(round(volume * 100))))

    def speak(self, text: str):
        self._voice.Speak(text)          # synchronous: returns when spoken

    def stop(self):
        try:
            self._pythoncom.CoUninitialize()
        except Exception:
            pass


class Pyttsx3Backend:
    """Cross-platform speech. espeak on Linux, NSSpeechSynthesizer on macOS."""

    def __init__(self, rate: int, volume: float):
        import pyttsx3
        self._engine = pyttsx3.init()
        self._engine.setProperty("rate", rate)
        self._engine.setProperty("volume", volume)

    def speak(self, text: str):
        self._engine.say(text)
        self._engine.runAndWait()

    def stop(self):
        try:
            self._engine.stop()
        except Exception:
            pass


def preferred_backends(is_windows: bool = IS_WINDOWS) -> list:
    """Backends to try, best first. Windows keeps pyttsx3 as a safety net."""
    if is_windows:
        return [Sapi5Backend, Pyttsx3Backend]
    return [Pyttsx3Backend]


# ==================================================================== speaker
class Speaker:
    # Any real utterance takes hundreds of milliseconds. A call that returns
    # faster than this did not actually make a sound.
    _MIN_UTTERANCE_S = 0.05

    def __init__(self, rate: int = 165, volume: float = 1.0):
        self.rate = rate
        self.volume = volume

        self._pending: tuple[int, str] | None = None
        self._spoken_at: dict[str, float] = {}
        self._last_message = ""
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self._running = False
        self.available = False
        self.error = ""
        self.recoveries = 0
        self.backend_name = "none"

    # ------------------------------------------------------------------ setup
    def start(self) -> "Speaker":
        self._running = True
        self._thread = threading.Thread(target=self._run, name="voice", daemon=True)
        self._thread.start()
        time.sleep(0.3)                      # let the backend report availability
        return self

    # Overridable so tests can inject a fake backend.
    @staticmethod
    def _backend_factory(rate: int, volume: float):
        last_error = None
        for cls in preferred_backends():
            try:
                return cls(rate, volume)
            except Exception as exc:
                last_error = exc
                print(f"[voice] {cls.__name__} unavailable ({exc})")
        raise RuntimeError(last_error or "no speech backend")

    def _make_backend(self):
        backend = self._backend_factory(self.rate, self.volume)
        self.backend_name = type(backend).__name__
        return backend

    # ------------------------------------------------------------------- api
    def say(self, text: str, priority: int = 3, cooldown_s: float = 3.0) -> bool:
        """Request speech. Returns True if accepted, False if suppressed.

        Suppressed when the identical phrase was spoken within cooldown_s, or
        when a strictly more urgent message is already waiting.
        """
        now = time.time()
        with self._lock:
            last = self._spoken_at.get(text)
            if last is not None and now - last < cooldown_s:
                return False
            if self._pending is not None and self._pending[0] < priority:
                return False
            self._pending = (priority, text)
        self._wake.set()
        return True

    def say_now(self, text: str) -> bool:
        """Bypass cooldown and priority. For P0 emergencies only."""
        with self._lock:
            self._pending = (-1, text)
        self._wake.set()
        return True

    @property
    def last_message(self) -> str:
        with self._lock:
            return self._last_message

    # ------------------------------------------------------------------- loop
    def _run(self):
        try:
            backend = self._make_backend()
            self.available = True
            print(f"[voice] speaking via {self.backend_name}")
        except Exception as exc:
            self.error = str(exc)
            print(f"[voice] TTS unavailable ({exc}) - falling back to text only")
            backend = None

        while self._running:
            if not self._wake.wait(timeout=0.2):
                continue
            self._wake.clear()

            with self._lock:
                item = self._pending
                self._pending = None
            if item is None:
                continue

            _, text = item
            with self._lock:
                self._spoken_at[text] = time.time()
                self._last_message = text

            if backend is None:
                print(f"[voice:text] {text}")
                continue
            backend = self._speak(backend, text)

        if backend is not None:
            backend.stop()

    def _speak(self, backend, text):
        """Speak one phrase, restarting the backend if it has gone silent."""
        for attempt in (1, 2):
            try:
                t0 = time.perf_counter()
                backend.speak(text)
                elapsed = time.perf_counter() - t0
            except Exception as exc:
                print(f"[voice] speak failed: {exc}")
                elapsed = 0.0

            # A muted run legitimately makes no sound, so never treat that as a
            # dead backend - it would rebuild the backend on every phrase.
            if elapsed >= self._MIN_UTTERANCE_S or self.volume <= 0.0:
                return backend
            if attempt == 2:
                print("[voice] still silent after a restart - "
                      "instructions will appear on screen only")
                return backend

            self.recoveries += 1
            print(f"[voice] engine went silent - restarting it "
                  f"(recovery #{self.recoveries})")
            try:
                backend.stop()
            except Exception:
                pass
            try:
                backend = self._make_backend()
            except Exception as exc:
                print(f"[voice] could not restart TTS: {exc}")
                return backend
        return backend

    def stop(self):
        self._running = False
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=3.0)
