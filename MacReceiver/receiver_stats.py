#!/usr/bin/env python3
"""
Wired Tooth receiver, statistics only. No audio, no dependencies.

This is the real-network test tool. It speaks enough of WTP1 to make the
sender stream to it -- HELLO keepalives, PING probes, BYE on exit -- and then
reports what actually arrived: loss, jitter, round-trip time, bitrate.

Deliberately no audio playback and deliberately stdlib-only. The point is to
separate "does the network carry this" from "does the audio pipeline work".
Loopback already proves the second. A Mac, a spare laptop or a Raspberry Pi
can run this with a stock Python and no pip install, which is what makes it
usable as the second device in a Wi-Fi test.

    python3 receiver_stats.py 172.20.10.14
    python3 receiver_stats.py 172.20.10.14 --seconds 60

Ctrl-C sends BYE and prints a summary.
"""
import argparse
import socket
import struct
import sys
import threading
import time

MAGIC = b"WTP1"
COMMON_HEADER = 20
AUDIO_HEADER = 28

AUDIO, HELLO, HELLO_ACK, PING, PONG, BYE = 1, 2, 3, 4, 5, 6
FLAG_SILENCE = 0x01

AUDIO_PORT = 5000
CONTROL_PORT = 5001

_START_NS = time.perf_counter_ns()


def now_us() -> int:
    """Microseconds since start, monotonic -- mirrors the C# Stopwatch."""
    return (time.perf_counter_ns() - _START_NS) // 1000


def parse(datagram: bytes):
    """
    Returns (header_dict, payload) or None. Never raises: this runs on data
    straight off a socket, and the C# receiver has the same contract.
    """
    if len(datagram) < COMMON_HEADER:
        return None
    if datagram[0:4] != MAGIC:
        return None

    ptype = datagram[4]
    if ptype < AUDIO or ptype > BYE:
        return None

    flags = datagram[5]
    payload_len, sequence, timestamp = struct.unpack_from("<HIq", datagram, 6)

    header_size = AUDIO_HEADER if ptype == AUDIO else COMMON_HEADER
    if len(datagram) < header_size:
        return None
    if payload_len != len(datagram) - header_size:
        return None

    h = {"type": ptype, "flags": flags, "seq": sequence, "ts": timestamp,
         "silence": bool(flags & FLAG_SILENCE)}

    if ptype == AUDIO:
        rate, channels = struct.unpack_from("<IH", datagram, 20)
        bits, codec = datagram[26], datagram[27]
        if rate == 0 or channels == 0 or bits == 0:
            return None
        h.update(rate=rate, channels=channels, bits=bits, codec=codec)

    return h, datagram[header_size:]


def write_control(ptype: int, seq: int, payload: bytes = b"") -> bytes:
    return (MAGIC + bytes([ptype, 0])
            + struct.pack("<HIq", len(payload), seq, now_us()) + payload)


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.packets = 0
        self.lost = 0
        self.dropped = 0
        self.silence = 0
        self.bytes = 0
        self.last_seq = None
        self.fmt = None
        self.rtts = []
        # Transit offset contains an unknown constant (the two clocks share no
        # origin), so only its movement is meaningful. Track the floor.
        self.min_offset = None
        self.jitter_peak = 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sender", help="IP address of the Windows sender")
    ap.add_argument("--seconds", type=float, default=0,
                    help="stop after N seconds (0 = until Ctrl-C)")
    ap.add_argument("--audio-port", type=int, default=AUDIO_PORT)
    ap.add_argument("--control-port", type=int, default=CONTROL_PORT)
    a = ap.parse_args()

    control_target = (a.sender, a.control_port)

    audio = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    audio.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    audio.bind(("0.0.0.0", a.audio_port))
    audio.settimeout(0.5)

    control = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    control.bind(("0.0.0.0", 0))
    control.settimeout(0.5)

    st = Stats()
    stop = threading.Event()
    seq = [0]

    def next_seq():
        seq[0] += 1
        return seq[0]

    def control_loop():
        """HELLO keepalive every 1s and a PING probe every 500ms."""
        last_hello = 0.0
        last_ping = 0.0
        while not stop.is_set():
            t = time.monotonic()
            try:
                if t - last_hello >= 1.0:
                    control.sendto(write_control(HELLO, next_seq()), control_target)
                    last_hello = t
                if t - last_ping >= 0.5:
                    control.sendto(write_control(PING, next_seq()), control_target)
                    last_ping = t
            except OSError:
                pass

            try:
                data, _ = control.recvfrom(2048)
            except (socket.timeout, OSError):
                continue
            parsed = parse(data)
            if not parsed:
                continue
            h, payload = parsed
            if h["type"] == PONG and len(payload) >= 8:
                sent_us = struct.unpack_from("<q", payload, 0)[0]
                rtt_ms = (now_us() - sent_us) / 1000.0
                if rtt_ms >= 0:
                    with st.lock:
                        st.rtts.append(rtt_ms)
                        if len(st.rtts) > 200:
                            del st.rtts[0]
            elif h["type"] == HELLO_ACK:
                pass
            elif h["type"] == BYE:
                print("sender said BYE")

    threading.Thread(target=control_loop, daemon=True).start()

    print(f"listening on UDP {a.audio_port}, HELLO -> {a.sender}:{a.control_port}")
    print("Ctrl-C to stop\n")

    started = time.monotonic()
    last_report = started
    last_packets = 0
    last_bytes = 0

    try:
        while not stop.is_set():
            try:
                data, _ = audio.recvfrom(2048)
            except socket.timeout:
                data = None
            except OSError:
                break

            if data:
                parsed = parse(data)
                if not parsed:
                    with st.lock:
                        st.dropped += 1
                else:
                    h, payload = parsed
                    if h["type"] == AUDIO:
                        with st.lock:
                            if st.fmt is None:
                                st.fmt = (h["rate"], h["channels"], h["bits"], h["codec"])
                                print(f"stream: {h['rate']} Hz, {h['channels']} ch, "
                                      f"{h['bits']}-bit, codec {h['codec']}")
                            if st.last_seq is not None and h["seq"] > st.last_seq + 1:
                                st.lost += h["seq"] - st.last_seq - 1
                            st.last_seq = h["seq"]
                            st.packets += 1
                            st.bytes += len(data)
                            if h["silence"]:
                                st.silence += 1
                            offset = now_us() - h["ts"]
                            if st.min_offset is None or offset < st.min_offset:
                                st.min_offset = offset
                            j = (offset - st.min_offset) / 1000.0
                            if j > st.jitter_peak:
                                st.jitter_peak = j

            t = time.monotonic()
            if t - last_report >= 1.0:
                with st.lock:
                    dp = st.packets - last_packets
                    db = st.bytes - last_bytes
                    last_packets, last_bytes = st.packets, st.bytes
                    total = st.packets + st.lost
                    loss = (st.lost / total * 100.0) if total else 0.0
                    rtts = sorted(st.rtts[-20:])
                    med = rtts[len(rtts) // 2] if rtts else 0.0
                    jp = st.jitter_peak
                    st.jitter_peak = 0.0
                elapsed = t - started
                kbit = db * 8 / 1000.0 / (t - last_report)
                print(f"[{elapsed:5.0f}s] pkts {st.packets:7d} | lost {st.lost:5d} "
                      f"({loss:5.2f}%) | {dp:4d}/s | {kbit:7.1f} kbit/s | "
                      f"rtt {med:6.2f} ms | jitter {jp:5.2f} ms")
                last_report = t

            if a.seconds and (time.monotonic() - started) >= a.seconds:
                break
    except KeyboardInterrupt:
        print("\ninterrupted")

    stop.set()
    try:
        control.sendto(write_control(BYE, next_seq()), control_target)
        print("sent BYE")
    except OSError:
        pass

    elapsed = max(time.monotonic() - started, 1e-6)
    total = st.packets + st.lost
    loss = (st.lost / total * 100.0) if total else 0.0
    rtts = sorted(st.rtts)
    print("\n=== SUMMARY ===")
    print(f"duration        : {elapsed:.1f} s")
    print(f"packets         : {st.packets}")
    print(f"  silence frames: {st.silence}")
    print(f"lost            : {st.lost}  ({loss:.2f}%)")
    print(f"malformed       : {st.dropped}")
    print(f"bitrate         : {st.bytes * 8 / 1000.0 / elapsed:.1f} kbit/s")
    if rtts:
        print(f"rtt median      : {rtts[len(rtts)//2]:.2f} ms")
        print(f"rtt p95         : {rtts[int(len(rtts)*0.95)]:.2f} ms")
        print(f"rtt max         : {rtts[-1]:.2f} ms")
    if st.fmt:
        print(f"format          : {st.fmt[0]} Hz, {st.fmt[1]} ch, "
              f"{st.fmt[2]}-bit, codec {st.fmt[3]}")
    print()
    print("PASS" if loss < 1.0 and st.packets > 0 else "FAIL",
          f"- CLAUDE.md requires loss under 1% on a real network "
          f"(measured {loss:.2f}%)")
    return 0 if (loss < 1.0 and st.packets > 0) else 1


if __name__ == "__main__":
    sys.exit(main())
