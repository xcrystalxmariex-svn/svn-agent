import zlib, struct, os, math

VIEW = 108.0
GREEN = (0, 255, 102)
DIM_FILL = (14, 47, 29)
BG = (5, 11, 8)
GRID = (0, 44, 23)
GLOW = (10, 37, 24)

SCREEN_PTS = [(30,30),(76,26),(86,54),(40,58)]
BASE_PTS   = [(30,57),(86,54),(82,76),(26,78)]
SEAM       = (32,60, 84,60)   # x1,y1,x2,y2
SEVEN      = [(30,40),(78,40),(40,84)]
SEVEN_W    = 13.0
STROKE_W   = 2.0

def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx-ax, by-ay
    wx, wy = px-ax, py-ay
    c1 = vx*wx + vy*wy
    if c1 <= 0: return math.hypot(px-ax, py-ay)
    c2 = vx*vx + vy*vy
    if c2 <= c1: return math.hypot(px-bx, py-by)
    b = c1/c2
    return math.hypot(px-(ax+b*vx), py-(ay+b*vy))

def point_in_poly(px, py, poly):
    inside = False
    j = len(poly)-1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > py) != (yj > py)) and (px < (xj-xi)*(py-yi)/(yj-yi)+xi):
            inside = not inside
        j = i
    return inside

def dist_to_polyline(px, py, pts, closed=False):
    d = float('inf')
    n = len(pts)
    for i in range(n):
        ax, ay = pts[i]
        if closed:
            bx, by = pts[(i+1) % n]
        elif i+1 < n:
            bx, by = pts[i+1]
        else:
            break
        d = min(d, seg_dist(px, py, ax, ay, bx, by))
    return d

def render(size):
    s = size / VIEW
    px = lambda x: x*s
    po = lambda p: [(x*s, y*s) for x, y in p]

    bg = bytes(255)+bytes([0,0,0,0])   # placeholder
    img = bytearray()
    for y in range(size):
        img.append(0)
        for x in range(size):
            col = BG
            # grid
            v = size/9.0
            if x % int(round(size/4.5)) == 0 or y % int(round(size/4.5)) == 0:
                col = GRID
            # glow circle
            if math.hypot(x-size/2, y-size/2) < (40*s):
                col = GLOW
            # laptop fill
            if point_in_poly(x, y, po(SCREEN_PTS)) or point_in_poly(x, y, po(BASE_PTS)):
                col = DIM_FILL
            # strokes
            for poly, closed in ((SCREEN_PTS, True), (BASE_PTS, True)):
                if dist_to_polyline(x, y, po(poly), closed) <= STROKE_W*0.5*s:
                    col = GREEN
            if seg_dist(x, y, px(SEAM[0]), px(SEAM[1]), px(SEAM[2]), px(SEAM[3])) <= STROKE_W*0.5*s:
                col = GREEN
            # big 7
            if dist_to_polyline(x, y, po(SEVEN)) <= SEVEN_W*0.5*s:
                col = GREEN
            img += bytes(col + (255,))
    return bytes(img)

def make_png(path, size):
    rows = render(size)
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        c += struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
        return c
    ihdr = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    raws = [rows[i*size*4:(i+1)*size*4] for i in range(size)]
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
    idat = zlib.compress(b''.join(b'\x00'+r for r in raws))
    png += chunk(b'IDAT', idat) + chunk(b'IEND', b'')
    open(path, 'wb').write(png)

for d, sz in (('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
              ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)):
    p = 'app/src/main/res/%s' % d
    os.makedirs(p, exist_ok=True)
    make_png('%s/ic_launcher.png' % p, sz)
    make_png('%s/ic_launcher_round.png' % p, sz)
    print('wrote', d, sz)