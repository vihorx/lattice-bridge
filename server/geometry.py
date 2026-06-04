"""
Geometry for the drone pose pipeline.

Conventions (matching DJI MSDK):
  - All angles in DEGREES at the API boundary, radians internally.
  - Aircraft yaw: 0 = north, positive clockwise (compass heading).
  - Gimbal yaw: relative to aircraft body (0 = forward).
  - Pitch: 0 = horizontal, negative = down (DJI convention: -90 = straight down).
  - Roll: ignored for now (Stage 1).
  - Lat/Lon in WGS84 degrees, altitude in meters above takeoff point.

Mavic 2 Zoom camera:
  - Sensor: 1/2.3" CMOS, ~6.17mm x 4.55mm active area.
  - Focal length: 24-48mm equivalent (35mm), actual ~4.3-8.6mm.
  - MSDK reports hybrid zoom focal length in 0.1mm units (e.g. 240 = 24mm equiv).
"""

import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

# Mavic 2 Zoom sensor dimensions (mm) - active imaging area
SENSOR_WIDTH_MM = 6.17
SENSOR_HEIGHT_MM = 4.55

# WGS84 ellipsoid for ENU conversion. Good to ~cm at our scale.
WGS84_A = 6378137.0
WGS84_F = 1 / 298.257223563
WGS84_E2 = WGS84_F * (2 - WGS84_F)


@dataclass
class DronePose:
    """Everything we need to compute where the camera is looking."""
    lat: float            # degrees
    lon: float            # degrees
    alt: float            # meters above takeoff
    aircraft_yaw: float   # degrees, 0=N, CW positive
    aircraft_pitch: float # degrees (small in flight)
    aircraft_roll: float  # degrees
    gimbal_yaw: float     # degrees, relative to aircraft
    gimbal_pitch: float   # degrees, -90 = straight down
    gimbal_roll: float    # degrees (usually 0)
    focal_length_mm: float  # actual mm, not 35mm-equivalent

    @property
    def camera_heading(self) -> float:
        """Absolute compass heading the camera is pointing (degrees, 0=N)."""
        return (self.aircraft_yaw + self.gimbal_yaw) % 360

    @property
    def camera_pitch(self) -> float:
        """Absolute pitch from horizontal (degrees)."""
        # Gimbal pitch is the dominant term; aircraft pitch contribution is small
        # and we ignore it for Stage 1.
        return self.gimbal_pitch

    @property
    def hfov_deg(self) -> float:
        """Horizontal field of view from focal length."""
        return 2 * math.degrees(math.atan(SENSOR_WIDTH_MM / (2 * self.focal_length_mm)))

    @property
    def vfov_deg(self) -> float:
        return 2 * math.degrees(math.atan(SENSOR_HEIGHT_MM / (2 * self.focal_length_mm)))


def ground_intersection(pose: DronePose, ground_alt: float = 0.0,
                        pixel_x: float = 0.5, pixel_y: float = 0.5) -> Optional[Tuple[float, float]]:
    """
    Project a normalized pixel coordinate (0..1, center=0.5) onto a flat ground
    plane at ground_alt meters (relative to takeoff).

    Returns (lat, lon) of the intersection or None if camera points up.

    Stage 1 only uses center pixel (0.5, 0.5) to draw the "where am I looking" cone.
    Stage 2 will use detection bbox centers.
    """
    # Build a ray in camera frame, rotate to world frame, intersect plane.
    # Camera frame: +X right, +Y down (image convention), +Z forward.

    hfov = math.radians(pose.hfov_deg)
    vfov = math.radians(pose.vfov_deg)

    # Pixel offset from center, in radians
    dx = (pixel_x - 0.5) * hfov
    dy = (pixel_y - 0.5) * vfov

    # Ray direction in camera frame (before yaw/pitch rotation)
    # Forward with small angular offsets
    rx = math.tan(dx)
    ry = math.tan(dy)
    rz = 1.0
    rlen = math.sqrt(rx*rx + ry*ry + rz*rz)
    rx, ry, rz = rx/rlen, ry/rlen, rz/rlen

    # Apply camera pitch (rotation about camera X axis: down is negative pitch)
    pitch = math.radians(pose.camera_pitch)
    cp, sp = math.cos(pitch), math.sin(pitch)
    # Rotate (rx, ry, rz) about X axis
    ry2 = ry * cp - rz * sp
    rz2 = ry * sp + rz * cp
    rx2 = rx

    # Apply camera heading (rotation about world Z axis / down axis)
    # heading 0 = north (+Y in ENU), heading 90 = east (+X in ENU)
    # Our camera-forward becomes ENU based on heading
    heading = math.radians(pose.camera_heading)
    ch, sh = math.cos(heading), math.sin(heading)
    # Camera forward (rz2) maps to north when heading=0; right (rx2) maps to east
    east  = rx2 * ch + rz2 * sh
    north = -rx2 * sh + rz2 * ch
    up    = -ry2  # camera-down (+Y) is world-down, so negate for ENU up

    # Now we have a unit ray in ENU centered at drone position.
    # Intersect with plane z = ground_alt - drone_alt (relative to drone)
    plane_z = ground_alt - pose.alt
    if up >= -1e-6:
        # Ray going up or horizontal -> no ground hit
        return None

    t = plane_z / up   # both negative -> positive t
    east_m = east * t
    north_m = north * t

    return enu_to_latlon(pose.lat, pose.lon, east_m, north_m)


def enu_to_latlon(lat0: float, lon0: float, east_m: float, north_m: float) -> Tuple[float, float]:
    """
    Convert local ENU offset (meters) to lat/lon, using flat-earth approximation
    referenced to lat0/lon0. Accurate to <1m for offsets up to a few km.
    """
    lat0_rad = math.radians(lat0)
    # Meters per degree
    m_per_deg_lat = 111132.92 - 559.82 * math.cos(2 * lat0_rad) + 1.175 * math.cos(4 * lat0_rad)
    m_per_deg_lon = 111412.84 * math.cos(lat0_rad) - 93.5 * math.cos(3 * lat0_rad)
    dlat = north_m / m_per_deg_lat
    dlon = east_m / m_per_deg_lon
    return lat0 + dlat, lon0 + dlon


def fov_cone_corners(pose: DronePose, ground_alt: float = 0.0) -> List[Tuple[float, float]]:
    """
    Returns lat/lon corners of the FOV footprint on the ground.
    Used to draw the "what the camera sees" polygon on the map.
    Order: top-left, top-right, bottom-right, bottom-left (image space).
    """
    corners = []
    for px, py in [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]:
        hit = ground_intersection(pose, ground_alt, px, py)
        if hit is None:
            # Cap the ray at 500m if it goes off into the sky
            hit = _capped_ray(pose, px, py, 500.0)
        corners.append(hit)
    return corners


def _capped_ray(pose: DronePose, px: float, py: float, max_dist_m: float) -> Tuple[float, float]:
    """Used when a ray doesn't hit the ground (camera tilted up)."""
    # Recompute ray (duplicated logic, kept for clarity in Stage 1)
    hfov = math.radians(pose.hfov_deg)
    vfov = math.radians(pose.vfov_deg)
    dx = (px - 0.5) * hfov
    dy = (py - 0.5) * vfov
    rx = math.tan(dx); ry = math.tan(dy); rz = 1.0
    rlen = math.sqrt(rx*rx + ry*ry + rz*rz)
    rx, ry, rz = rx/rlen, ry/rlen, rz/rlen
    pitch = math.radians(pose.camera_pitch)
    cp, sp = math.cos(pitch), math.sin(pitch)
    ry2 = ry * cp - rz * sp
    rz2 = ry * sp + rz * cp
    rx2 = rx
    heading = math.radians(pose.camera_heading)
    ch, sh = math.cos(heading), math.sin(heading)
    east  = rx2 * ch + rz2 * sh
    north = -rx2 * sh + rz2 * ch
    # Project on horizontal plane only
    h = math.sqrt(east*east + north*north)
    if h < 1e-6:
        return pose.lat, pose.lon
    east_m = east / h * max_dist_m
    north_m = north / h * max_dist_m
    return enu_to_latlon(pose.lat, pose.lon, east_m, north_m)
