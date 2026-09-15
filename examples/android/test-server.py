"""HTTP acceptance stub for API 26 CI. Does not replace real Worker integration."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.path == '/v1/events'
        assert set(body) == {'context', 'identity', 'local_time', 'events'}
        assert body['context']['app_id'] == 'demo'
        assert body['context']['platform'] == 'android'
        assert body['context']['os_name'] == 'Android'
        assert isinstance(body['identity'], dict)
        assert len(body['events']) > 0
        for event in body['events']:
            assert event['event_id'] and event['event_name'] and event['local_time_ms'] > 0
        self.send_response(202)
        self.send_header('Content-Length', '0')
        self.end_headers()

ThreadingHTTPServer(('127.0.0.1', 8787), Handler).serve_forever()
