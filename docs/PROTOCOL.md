# Wired Tooth Protocol (WTP1)

Placeholder. The wire format is specified and implemented in WP1.

Until then the prototype uses an informal 12-byte header
(uint32 sequence, uint32 timestamp_ms, uint32 payload_length) followed by raw
32-bit float PCM. That format has no magic number and no microsecond timestamp,
so it cannot be validated or used for latency measurement. WP1 replaces it.
