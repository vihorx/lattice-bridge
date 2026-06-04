"""
Fake drone telemetry generator.

Flies a slow circle over a fixed point and pans the gimbal back and forth.
Compass error oscillates so we can verify UI both states.

Run:
  python simulator.py
  # in another terminal:  python app.py
"""

import json
import math
import socket
import time

UDP_HOST = '127.0.0.1'
UDP_PORT = 14550

CENTER_LAT = 44.7866
CENTER_LON = 20.4489
RADIUS_M = 80.0
ALTITUDE_M = 60.0
PERIOD_S = 40.0

def latlon_offset(lat, lon, east_m, north_m):
    lat_rad = math.radians(lat)
    m_per_deg_lat = 111132.92 - 559.82 * math.cos(2*lat_rad)
    m_per_deg_lon = 111412.84 * math.cos(lat_rad) - 93.5 * math.cos(3*lat_rad)
    return lat + north_m/m_per_deg_lat, lon + east_m/m_per_deg_lon


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(f'[sim] sending to {UDP_HOST}:{UDP_PORT} at 10 Hz')

    t0 = time.time()
    while True:
        t = time.time() - t0

        angle = 2 * math.pi * (t / PERIOD_S)
        east = RADIUS_M * math.cos(angle)
        north = RADIUS_M * math.sin(angle)
        lat, lon = latlon_offset(CENTER_LAT, CENTER_LON, east, north)

        east_vel = -RADIUS_M * math.sin(angle) * (2*math.pi/PERIOD_S)
        north_vel =  RADIUS_M * math.cos(angle) * (2*math.pi/PERIOD_S)
        ac_yaw = (math.degrees(math.atan2(east_vel, north_vel))) % 360

        g_yaw = 45.0 * math.sin(2 * math.pi * t / 12.0)
        g_pitch = -37.5 + 22.5 * math.sin(2 * math.pi * t / 9.0)
        focal_mm = 4.3 + (8.6 - 4.3) * (0.5 + 0.5 * math.sin(2 * math.pi * t / 15.0))
        speed = math.sqrt(east_vel**2 + north_vel**2)

        # Compass error pulsira: 5s ON, 15s OFF (20s ciklus)
        compass_error = (t % 20.0) < 5.0

        msg = {
            'lat': lat,
            'lon': lon,
            'alt': ALTITUDE_M,
            'ac_yaw': ac_yaw,
            'ac_pitch': 0.0,
            'ac_roll': 0.0,
            'g_yaw': g_yaw,
            'g_pitch': g_pitch,
            'g_roll': 0.0,
            'focal_mm': focal_mm,
            'speed': speed,
            'sats': 14,
            'battery': 78,
            'compass_error': compass_error,
        }
        sock.sendto(json.dumps(msg).encode('utf-8'), (UDP_HOST, UDP_PORT))
        time.sleep(0.1)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n[sim] stopped')
