"""
Lattice ground station - Stage 1+.

Architecture:
  Android (drone bridge) --UDP JSON--> this server --SocketIO--> browser map

Run:
  python app.py
Then open http://localhost:5000 in any browser on the same network.
"""

import json
import socket
import threading
import time
from flask import Flask, render_template
from flask_socketio import SocketIO

from geometry import DronePose, fov_cone_corners, ground_intersection

UDP_PORT = 14550
WEB_PORT = 5000

app = Flask(__name__)
app.config['SECRET_KEY'] = 'dev'
sio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

_state = {
    'pose': None,
    'last_packet_time': 0,
}


@app.route('/')
def index():
    return render_template('map.html')


@sio.on('connect')
def on_connect():
    print('[web] client connected')


def udp_listener():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', UDP_PORT))
    print(f'[udp] listening on :{UDP_PORT}')

    while True:
        data, addr = sock.recvfrom(2048)
        try:
            msg = json.loads(data.decode('utf-8'))
        except Exception as e:
            print(f'[udp] bad packet from {addr}: {e}')
            continue

        process_telemetry(msg)


def process_telemetry(msg: dict):
    try:
        pose = DronePose(
            lat=msg['lat'],
            lon=msg['lon'],
            alt=msg['alt'],
            aircraft_yaw=msg['ac_yaw'],
            aircraft_pitch=msg.get('ac_pitch', 0.0),
            aircraft_roll=msg.get('ac_roll', 0.0),
            gimbal_yaw=msg.get('g_yaw', 0.0),
            gimbal_pitch=msg.get('g_pitch', 0.0),
            gimbal_roll=msg.get('g_roll', 0.0),
            focal_length_mm=msg.get('focal_mm', 4.3),
        )
    except KeyError as e:
        print(f'[udp] missing field {e} in {msg}')
        return

    _state['pose'] = pose
    _state['last_packet_time'] = time.time()

    look_at = ground_intersection(pose)
    fov_corners = fov_cone_corners(pose)

    # Stage 4A korak 2: georeferencing YOLO detekcija
    # Telefon salje normalized bbox centar (nx, ny) u 0..1 space-u original bitmap-a.
    # Mi pozivamo isti ground_intersection() math koji vec radi za "look_at" tacku.
    detections_out = []
    for d in msg.get('detections', []):
        nx = d.get('nx', 0.5)
        ny = d.get('ny', 0.5)
        hit = ground_intersection(pose, ground_alt=0.0, pixel_x=nx, pixel_y=ny)
        if hit is not None:
            detections_out.append({
                'lat': hit[0],
                'lon': hit[1],
                'label': d.get('label', '?'),
                'conf': d.get('conf', 0.0),
            })


    payload = {
        'lat': pose.lat, 'lon': pose.lon, 'alt': pose.alt,
        'heading': pose.camera_heading,
        'cam_pitch': pose.camera_pitch,
        'hfov': pose.hfov_deg,
        'focal_mm': pose.focal_length_mm,
        'look_at': look_at,
        'fov_corners': fov_corners,
        'speed': msg.get('speed', 0.0),
        'sats': msg.get('sats', 0),
        'battery': msg.get('battery', None),
        'compass_error': msg.get('compass_error', False),
        'detections': detections_out,
    }
    sio.emit('telemetry', payload)


if __name__ == '__main__':
    t = threading.Thread(target=udp_listener, daemon=True)
    t.start()
    print(f'[web] map at http://localhost:{WEB_PORT}')
    sio.run(app, host='0.0.0.0', port=WEB_PORT, allow_unsafe_werkzeug=True)
