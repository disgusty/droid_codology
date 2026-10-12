"""Prints a uiautomator dump (ui.xml) as an indented tree of class names and bounds."""
import sys
import xml.etree.ElementTree as ET


def walk(node, depth):
    for child in node:
        if child.tag != "node":
            continue
        cls = child.attrib.get("class", "").split(".")[-1]
        text = child.attrib.get("text", "") or child.attrib.get("content-desc", "")
        print("  " * depth + cls, child.attrib.get("bounds", ""), text[:40])
        walk(child, depth + 1)


walk(ET.parse(sys.argv[1]).getroot(), 0)
