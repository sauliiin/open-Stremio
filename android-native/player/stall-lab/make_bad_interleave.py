#!/usr/bin/env python3
"""Rewrites an MKV so its tracks are badly interleaved.

    make_bad_interleave.py in.mkv out.mkv <film seconds> <stretch seconds>

For each stretch of the film, every audio block is written before any video block.
A demuxer reading in file order then has minutes of audio queued before the first
video frame of that stretch arrives, which is how a badly muxed remux behaves. Muxers
refuse to produce this on purpose, so the blocks are moved by hand. The index is
dropped, because it would point at the old positions.
"""
import struct
import sys

ID_SEGMENT = 0x18538067
ID_CLUSTER = 0x1F43B675
ID_INFO = 0x1549A966
ID_TRACKS = 0x1654AE6B
ID_TIMECODE = 0xE7
ID_SIMPLE_BLOCK = 0xA3
ID_BLOCK_GROUP = 0xA0
ID_BLOCK = 0xA1
UNKNOWN_SIZE = b"\x01\xff\xff\xff\xff\xff\xff\xff"


def read_id(data, pos):
    first = data[pos]
    length = 1
    mask = 0x80
    while not first & mask:
        mask >>= 1
        length += 1
    return int.from_bytes(data[pos:pos + length], "big"), pos + length


def read_size(data, pos):
    first = data[pos]
    length = 1
    mask = 0x80
    while not first & mask:
        mask >>= 1
        length += 1
    value = first & (mask - 1)
    for byte in data[pos + 1:pos + length]:
        value = (value << 8) | byte
    unknown = value == (1 << (7 * length)) - 1
    return (None if unknown else value), pos + length


def encode_id(element_id):
    length = (element_id.bit_length() + 7) // 8
    return element_id.to_bytes(length, "big")


def encode_size(value):
    return (value | (1 << 56)).to_bytes(8, "big")


def element(element_id, payload):
    return encode_id(element_id) + encode_size(len(payload)) + payload


def blocks_of(data, start, end, cluster_ms):
    pos = start
    while pos < end:
        element_id, pos = read_id(data, pos)
        size, pos = read_size(data, pos)
        payload_end = pos + size
        if element_id in (ID_SIMPLE_BLOCK, ID_BLOCK):
            track, after_track = read_size(data, pos)
            relative = struct.unpack(">h", data[after_track:after_track + 2])[0]
            flags = data[after_track + 2]
            yield track, cluster_ms + relative, flags, data[after_track + 3:payload_end]
        elif element_id == ID_BLOCK_GROUP:
            yield from blocks_of(data, pos, payload_end, cluster_ms)
        pos = payload_end


def write_cluster(out, start_ms, blocks):
    payload = bytearray(element(ID_TIMECODE, start_ms.to_bytes(8, "big")))
    for track, at_ms, flags, frame in blocks:
        header = bytes([0x80 | track]) + struct.pack(">h", at_ms - start_ms) + bytes([flags])
        payload += element(ID_SIMPLE_BLOCK, header + frame)
    out.write(element(ID_CLUSTER, bytes(payload)))


def write_clusters(out, blocks):
    # A relative timestamp only holds 16 bits, so a cluster covers under 30 seconds.
    run = []
    for block in blocks:
        if run and block[1] - run[0][1] >= 30_000:
            write_cluster(out, run[0][1], run)
            run = []
        run.append(block)
    if run:
        write_cluster(out, run[0][1], run)


def main():
    source, target, film_s, stretch_s = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    with open(source, "rb") as f:
        data = f.read()

    pos = 0
    element_id, pos = read_id(data, pos)
    size, pos = read_size(data, pos)
    ebml_header = data[:pos + size]
    pos += size

    element_id, pos = read_id(data, pos)
    assert element_id == ID_SEGMENT
    segment_size, pos = read_size(data, pos)
    end = len(data) if segment_size is None else pos + segment_size

    head = bytearray()
    stretches = {}
    while pos < end:
        at = pos
        element_id, pos = read_id(data, pos)
        size, pos = read_size(data, pos)
        if element_id in (ID_INFO, ID_TRACKS):
            head += data[at:pos + size]
        elif element_id == ID_CLUSTER:
            cluster_end = pos + size
            inner, cluster_ms = pos, 0
            while inner < cluster_end:
                inner_id, inner_at = read_id(data, inner)
                inner_size, inner_at = read_size(data, inner_at)
                if inner_id == ID_TIMECODE:
                    cluster_ms = int.from_bytes(data[inner_at:inner_at + inner_size], "big")
                    break
                inner = inner_at + inner_size
            if cluster_ms < film_s * 1000:
                for block in blocks_of(data, pos, cluster_end, cluster_ms):
                    if block[1] < film_s * 1000:
                        index = block[1] // (stretch_s * 1000)
                        stretches.setdefault(index, []).append(block)
        pos += size

    with open(target, "wb") as out:
        out.write(ebml_header)
        out.write(encode_id(ID_SEGMENT))
        out.write(UNKNOWN_SIZE)
        out.write(head)
        for index in sorted(stretches):
            blocks = stretches[index]
            # Track 1 is the video in this fixture.
            write_clusters(out, sorted((b for b in blocks if b[0] != 1), key=lambda b: b[1]))
            write_clusters(out, sorted((b for b in blocks if b[0] == 1), key=lambda b: b[1]))


if __name__ == "__main__":
    sys.exit(main())
