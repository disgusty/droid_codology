"""Smart-HTTP Git server for the droid_codology git tests (needs `pip install dulwich`).

    python tools/git_test_server.py PORT REPO_DIR [--init]

--init creates a bare repository with a few commits (including a big file edited several
times, packed with deltas) so clients have something to clone, fetch and push to.
Push (receive-pack) is enabled; no authentication.
"""
import os
import sys
import time
from wsgiref.simple_server import make_server, WSGIRequestHandler

from dulwich.repo import Repo
from dulwich.server import DictBackend
from dulwich.web import make_wsgi_chain, WSGIRequestHandlerLogger, WSGIServerLogger
from dulwich.objects import Blob, Tree, Commit


def seed(path):
    r = Repo.init_bare(path, mkdir=True)
    parent = None
    big = "".join("line %d of a long file used to produce deltas\n" % i for i in range(3000))
    for n in range(4):
        files = {
            b"README.md": ("# Test repo\n\nrevision %d\n" % n).encode(),
            b"big.txt": (big + "edit %d\n" % n).encode(),
        }
        sub = Tree()
        hello = Blob.from_string(b'print("hello")\n')
        r.object_store.add_object(hello)
        sub.add(b"hello.py", 0o100644, hello.id)
        r.object_store.add_object(sub)
        tree = Tree()
        for name, data in files.items():
            b = Blob.from_string(data)
            r.object_store.add_object(b)
            tree.add(name, 0o100644, b.id)
        tree.add(b"src", 0o040000, sub.id)
        r.object_store.add_object(tree)
        c = Commit()
        c.tree = tree.id
        c.parents = [parent] if parent else []
        c.author = c.committer = b"Seeder <seed@example.com>"
        c.commit_time = c.author_time = int(time.time()) - 1000 + n
        c.commit_timezone = c.author_timezone = 0
        c.message = ("commit %d\n" % n).encode()
        r.object_store.add_object(c)
        parent = c.id
    r.refs[b"refs/heads/main"] = parent
    r.refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
    # Pack everything with deltas so clients must resolve them.
    r.object_store.pack_loose_objects()
    try:
        r.object_store.repack()
    except Exception:
        pass
    return r


def main():
    import logging
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr)
    port, path = int(sys.argv[1]), sys.argv[2]
    repo = seed(path) if "--init" in sys.argv else Repo(path)
    backend = DictBackend({"/": repo})
    app = make_wsgi_chain(backend)

    class Quiet(WSGIRequestHandlerLogger):
        def log_message(self, *a):
            pass

    server = make_server("127.0.0.1", port, app, handler_class=Quiet, server_class=WSGIServerLogger)
    print("ready", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
