"""Private loopback TCP fault fixture for an acceptance run, with owned sockets only."""
import select
import socket
import socketserver
import threading


class TcpGate:
    def __init__(self,host,port):
        self.target=(host,port);self.enabled=True;self.lock=threading.Lock();self.pairs=set()
        gate=self
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                upstream=None;pair=None
                try:
                    with gate.lock:
                        if not gate.enabled:return
                    upstream=socket.create_connection(gate.target,timeout=2)
                    pair=(self.request,upstream)
                    with gate.lock:
                        if not gate.enabled:return
                        gate.pairs.add(pair)
                    while gate.enabled:
                        readable,_,_=select.select(pair,[],[],.2)
                        for current in readable:
                            data=current.recv(65536)
                            if not data:return
                            (upstream if current is self.request else self.request).sendall(data)
                except (OSError,ValueError):pass
                finally:
                    if pair:
                        with gate.lock:gate.pairs.discard(pair)
                    if upstream:upstream.close()
        class Server(socketserver.ThreadingTCPServer):
            daemon_threads=True
            block_on_close=False
        self.server=Server(('127.0.0.1',0),Handler)
        self.port=self.server.server_address[1]
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()

    def set_enabled(self,enabled):
        with self.lock:
            self.enabled=enabled
            pairs=list(self.pairs) if not enabled else []
        for pair in pairs:
            for connection in pair:
                try:connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                connection.close()

    def close(self):
        self.set_enabled(False);self.server.shutdown();self.server.server_close();self.thread.join(timeout=3)
