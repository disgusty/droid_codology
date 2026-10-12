"""Creates a small multi-language demo project (plus a huge file) for testing on devices."""
import os
import sys

out = sys.argv[1] if len(sys.argv) > 1 else "demo"
os.makedirs(os.path.join(out, "src", "main"), exist_ok=True)
os.makedirs(os.path.join(out, "web"), exist_ok=True)
os.makedirs(os.path.join(out, "scripts"), exist_ok=True)

files = {
    "README.md": """# Demo project

This is **droid_codology** test content with `inline code` and a [link](https://example.com).

- item one
- item _two_

```java
int x = 42;
```
""",
    "src/main/Main.java": """package demo;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Shows highlighting of a doc comment.
 */
public class Main {
    private static final int MAX = 0x1F;

    @Override
    public String toString() {
        return "Main{" + MAX + "}\\n";
    }

    public static void main(String[] args) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            if (i % 2 == 0) {
                names.add("item " + i);
            } else {
                // odd numbers are skipped
                continue;
            }
        }
        System.out.println(names.size() + " items");
    }
}
""",
    "web/index.html": """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Demo</title>
  <style>
    body { margin: 0; color: #333; font-family: sans-serif; }
    .card:hover { background: rgba(0, 0, 0, 0.05); }
  </style>
</head>
<body>
  <!-- a comment -->
  <div class="card" id="main">Hello &amp; welcome</div>
  <script>
    const el = document.getElementById('main');
    el.addEventListener('click', () => {
      console.log(`clicked ${el.id}`, /ab+c/gi.test('abbc'));
    });
  </script>
</body>
</html>
""",
    "web/app.ts": """interface User {
  id: number;
  name: string;
  email?: string;
}

export async function load(url: string): Promise<User[]> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as User[];
}
""",
    "scripts/build.py": '''#!/usr/bin/env python3
"""Build helper."""
import os


class Builder:
    def __init__(self, root: str):
        self.root = root

    @property
    def name(self):
        return os.path.basename(self.root)

    def run(self, verbose=False):
        for i in range(10):
            if verbose:
                print(f"step {i}")
        return True
''',
    "scripts/deploy.sh": """#!/bin/bash
set -e
TARGET=${1:-prod}
echo "Deploying to $TARGET"
for f in *.txt; do
  cp "$f" /tmp/   # copy
done
""",
    "config.json": """{
  "name": "demo",
  "version": "1.0.0",
  "private": true,
  "scripts": { "build": "tsc -p .", "test": null },
  "count": 3.5e2
}
""",
    "settings.yaml": """# Settings
server:
  host: localhost
  port: 8080
  debug: true
users:
  - name: alice
""",
    "main.c": """#include <stdio.h>
#define SQUARE(x) ((x) * (x))

int main(void) {
    unsigned long n = 10UL;
    printf("%lu\\n", SQUARE(n));
    return 0;
}
""",
    "crlf-cp1251.txt": None,
}
for name, text in files.items():
    path = os.path.join(out, name)
    if text is None:
        with open(path, "wb") as f:
            f.write("Привет, мир!\r\nВторая строка\r\n".encode("cp1251"))
        continue
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)

# A large file to check speed: ~200k lines of Java-like code.
with open(os.path.join(out, "src", "main", "Big.java"), "w", encoding="utf-8", newline="\n") as f:
    f.write("public class Big {\n")
    for i in range(200000):
        f.write("    int field%d = %d; // comment %d \"str\"\n" % (i, i * 7, i))
    f.write("}\n")
print("demo written to", out)
