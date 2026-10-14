"""uiautomator XML parsing and lookup. Compose text shows up as the `text` attribute of nodes."""
import re
import xml.etree.ElementTree as ET
from collections import namedtuple

Node = namedtuple("Node", "text desc rid cls clickable bounds")
_B = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_dump(xml_text):
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
    if start < 0:
        return []
    try:
        root = ET.fromstring(xml_text[start:].strip())
    except ET.ParseError:
        return []
    out = []
    for e in root.iter("node"):
        m = _B.match(e.get("bounds", ""))
        if not m:
            continue
        out.append(Node(e.get("text", ""), e.get("content-desc", ""), e.get("resource-id", ""),
                        e.get("class", ""), e.get("clickable") == "true", tuple(map(int, m.groups()))))
    return out


def center(n):
    l, t, r, b = n.bounds
    return (l + r) // 2, (t + b) // 2


def find_all(nodes, text=None, desc=None, regex=None, contains=None):
    """text/desc: exact match; regex: re.search on text or desc; contains: substring of text or desc."""
    rx = re.compile(regex) if regex else None
    out = []
    for n in nodes:
        if text is not None and n.text != text:
            continue
        if desc is not None and n.desc != desc:
            continue
        if rx and not (rx.search(n.text) or rx.search(n.desc)):
            continue
        if contains is not None and contains not in n.text and contains not in n.desc:
            continue
        if text is None and desc is None and rx is None and contains is None:
            continue
        out.append(n)
    return out


def find(nodes, **kw):
    r = find_all(nodes, **kw)
    return r[0] if r else None


def inside(inner, outer):
    return outer[0] <= inner[0] and outer[1] <= inner[1] and inner[2] <= outer[2] and inner[3] <= outer[3]


def texts_in(nodes, bounds):
    return [n.text for n in nodes if n.text and inside(n.bounds, bounds)]
