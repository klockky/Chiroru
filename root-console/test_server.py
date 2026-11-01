import array
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAGIC = 0x43504301


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.binary = str(Path(cls.temp.name) / 'server')
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', '-D_FORTIFY_SOURCE=2', '-ffunction-sections',
                        '-fdata-sections', '-Wl,--gc-sections', '-DCONSOLE_LIBRARY',
                        '-DCONSOLE_SHELL="/bin/sh"', str(ROOT / 'root-console/test_server_driver.c'),
                        str(ROOT / 'app/Chiroru/app/src/main/cpp/console_runner.c'), '-o', cls.binary], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.client, server = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.client.settimeout(3)
        self.process = subprocess.Popen([self.binary, str(server.fileno())], pass_fds=(server.fileno(),))
        server.close()
        self.assertEqual(self.reply(), 0)

    def tearDown(self):
        self.client.close()
        try:
            self.assertEqual(self.process.wait(timeout=3), 0)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()

    def reply(self):
        magic, status = struct.unpack('=Ii', self.client.recv(8))
        self.assertEqual(magic, MAGIC)
        return status

    def start(self, command):
        output, control, status = os.pipe(), os.pipe(), os.pipe()
        fds = array.array('i', [output[1], control[0], status[1]])
        self.client.sendmsg([struct.pack('=III', MAGIC, 2, len(command)), command],
                            [(socket.SOL_SOCKET, socket.SCM_RIGHTS, fds)])
        for fd in fds:
            os.close(fd)
        self.assertEqual(self.reply(), 0)
        return (os.fdopen(output[0], 'rb'), os.fdopen(control[1], 'wb'), os.fdopen(status[0], 'rb'))

    def test_repeated_commands_and_live_ping(self):
        for _ in range(4):
            self.client.send(struct.pack('=III', MAGIC, 1, 0))
            self.assertEqual(self.reply(), 0)
            output, control, status = self.start(b'printf hello; exit 7')
            with output, control, status:
                self.assertEqual(output.read(), b'hello')
                self.assertEqual(struct.unpack('!I', status.read(4))[0], 7)

    def test_stop_and_session_disconnect_cleanup(self):
        output, control, status = self.start(b'echo ready; sleep 60 | cat')
        with output, control, status:
            self.assertEqual(output.readline(), b'ready\n')
            control.close()
            self.client.close()
            self.assertEqual(output.read(), b'')
            self.assertEqual(struct.unpack('!I', status.read(4))[0], 130)

    def test_malformed_messages_rejected_without_losing_session(self):
        for packet in (b'x', struct.pack('=III', MAGIC, 99, 0),
                       struct.pack('=III', MAGIC, 2, 3) + b'id',
                       struct.pack('=III', MAGIC, 2, 2) + b'id'):
            self.client.send(packet)
            self.assertEqual(self.reply(), -1)
        self.client.send(struct.pack('=III', MAGIC, 1, 0))
        self.assertEqual(self.reply(), 0)


if __name__ == '__main__':
    unittest.main()
