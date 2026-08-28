#!/usr/bin/env python3
"""
scheda2dxf v4 — uses ezdxf as DXF engine for guaranteed compatibility.
"""
import io, json, math, sys, re, struct
from decimal import Decimal, ROUND_HALF_UP
import ezdxf
from ezdxf.math import Vec2
from ezdxf.enums import TextEntityAlignment

# 最近一次导出的告警（实体级失败等），Kotlin 侧通过 get_last_warnings() 读取
_WARNINGS = []

def get_last_warnings():
    return "\n".join(_WARNINGS)

# 导出文字统一使用的字体样式（黑体；AutoCAD 按 simhei.ttf 解析系统字体）
_TEXT_STYLE = 'SimHei'

def _ensure_text_style(doc):
    if not doc.styles.has_entry(_TEXT_STYLE):
        doc.styles.add(_TEXT_STYLE, font='simhei.ttf')

# ── Precision & helpers ──

def to_f32(v):
    return struct.unpack('f', struct.pack('f', float(v)))[0]

def d2s(d):
    f32 = to_f32(d)
    s = str(Decimal(str(f32)).quantize(Decimal('0.001'), rounding=ROUND_HALF_UP))
    s = s.rstrip('0')
    if s.endswith('.'):
        s += '0'
    return s

def fy(y):
    return -float(y)

# ── ACI → color mapping ──
ACI_PALETTE = [(255,0,0,1),(255,255,0,2),(0,255,0,3),(0,255,255,4),
               (0,0,255,5),(255,0,255,6),(255,255,255,7),(0,0,0,7),
               (128,128,128,8),(192,192,192,9),(255,165,0,40),(165,42,42,14)]

def color_to_aci(r, g, b):
    best, best_d = 7, float('inf')
    for pr, pg, pb, pa in ACI_PALETTE:
        d = (pr-r)**2 + (pg-g)**2 + (pb-b)**2
        if d < best_d: best_d, best = d, pa
    return best


# ── Main ──

# DXF 层名非法字符（R2000+）：< > / \ " : ; ? * | = , `
_INVALID_LAYER_CHARS = re.compile(r'[<>/\\":;?*|=,`]')

def _sanitize_layer_name(name, taken):
    """净化 DXF 图层名：替换非法字符、保证非空且在文档内唯一（层名不区分大小写）。"""
    s = _INVALID_LAYER_CHARS.sub('_', str(name)).strip()
    if not s:
        s = 'Layer'
    base, i = s, 2
    while s.lower() in taken:
        s = f"{base}_{i}"
        i += 1
    taken.add(s.lower())
    return s

def _build_layer_table(doc, layers_data):
    """建 LAYER 表，返回 {app图层id: DXF层名}。
    隐藏图层的实体照常导出，但图层在 DXF 中置为 OFF（AutoCAD 中显示为关闭）。"""
    name_map = {}
    taken = {'0', 'defpoints'}  # DXF 默认保留层
    for l in layers_data:
        ln = str(l.get('name', ''))
        if ln in ('0', '图层0'):
            name_map[l.get('id', 0)] = '0'  # DXF 默认存在层 "0"
            continue
        s = _sanitize_layer_name(ln, taken)
        name_map[l.get('id', 0)] = s
        try:
            layer = doc.layers.add(name=s)
            if not l.get('isVisible', True):
                layer.off()
        except Exception as e:
            _WARNINGS.append(f"layer {s}: {e}")
            print(f"  Skipping layer {s}: {e}", file=sys.stderr)
    return name_map

def scheda_to_dxf(scheda_path, output_path):
    
    with open(scheda_path, 'r', encoding='utf-8') as f:
        scheda = json.load(f)
    
    primitives = scheda.get('primitives', [])
    layers_data = scheda.get('layers', [{'id': 0, 'name': '图层0', 'isVisible': True, 'color': -16777216}])
    
    # Create minimal DXF document
    doc = ezdxf.new("AC1015")
    _ensure_text_style(doc)
    
    # LAYER 表（含名称净化与隐藏层 OFF 状态）
    name_map = _build_layer_table(doc, layers_data)
    
    # Set encoding
    doc.encoding = 'gbk'
    
    msp = doc.modelspace()
    
    # Process each primitive
    for p in primitives:
        try:
            _write_primitive(msp, p, doc, name_map)
        except Exception as e:
            _WARNINGS.append(f"{p.get('type', '?')}: {e}")
            print(f"  Skipping entity: {e}", file=sys.stderr)
    
    doc.saveas(output_path)
    print(f"Done: {output_path}", file=sys.stderr)


def _write_primitive(msp, p, doc, name_map):
    pt = p.get('type', '')
    lid = p.get('layerId', 0)
    # 与 LAYER 表一致的净化后层名；未知图层归入 "0"
    layer_name = name_map.get(lid, "0")
    c = p.get('color', -16777216)
    aci = color_to_aci((c>>16)&0xFF, (c>>8)&0xFF, c&0xFF)
    lt = p.get('lineType', 'SOLID')
    
    dxf_attribs = {'layer': layer_name}
    if aci != 7:
        dxf_attribs['color'] = aci
    
    if pt == 'line':
        sx, sy = p['startX'], p['startY']
        ex, ey = p['endX'], p['endY']
        if lt == 'LIGHTNING':
            msp.add_line((sx, fy(sy)), (ex, fy(ey)), dxfattribs=dxf_attribs)
            _lightning_x(msp, sx, sy, ex, ey, aci, layer_name)
        else:
            if lt == 'DASHED':
                dxf_attribs['linetype'] = 'DASHED'
            msp.add_line((sx, fy(sy)), (ex, fy(ey)), dxfattribs=dxf_attribs)
    
    elif pt == 'rectangle':
        # corners = AABB 四角；渲染语义 = AABB 绕中心按 rotation 旋转
        cs = p.get('corners') or []
        if len(cs) >= 4:
            xs = [cp[0] for cp in cs]; ys = [cp[1] for cp in cs]
            x1, x2 = min(xs), max(xs)
            y1, y2 = min(ys), max(ys)
        else:
            # 兼容旧格式 startX/startY/endX/endY
            x1, y1 = min(p['startX'], p['endX']), min(p['startY'], p['endY'])
            x2, y2 = max(p['startX'], p['endX']), max(p['startY'], p['endY'])
        rot = p.get('rotation', 0.0)
        d_attr = {**dxf_attribs}
        if lt == 'DASHED':
            d_attr['linetype'] = 'DASHED'
        
        # Define 4 edges
        if abs(rot) < 0.001:
            edges = [(x1,y1,x2,y1),(x2,y1,x2,y2),(x2,y2,x1,y2),(x1,y2,x1,y1)]
        else:
            cx, cy = (x1+x2)/2, (y1+y2)/2
            cr, sr = math.cos(rot), math.sin(rot)
            def rp(wx, wy):
                dx, dy = wx-cx, wy-cy
                return (cx+dx*cr-dy*sr, cy+dx*sr+dy*cr)
            pts = [rp(x1,y1), rp(x2,y1), rp(x2,y2), rp(x1,y2)]
            edges = [(pts[i][0],pts[i][1],pts[(i+1)%4][0],pts[(i+1)%4][1]) for i in range(4)]
        
        for ex1, ey1, ex2, ey2 in edges:
            msp.add_line((ex1, fy(ey1)), (ex2, fy(ey2)), dxfattribs=d_attr)
            if lt == 'LIGHTNING':
                _lightning_x(msp, ex1, ey1, ex2, ey2, aci, layer_name)
    
    elif pt == 'freehand':
        pts = p['points']
        closed = p.get('isClosed', False)
        if len(pts) < 2: return
        
        if lt == 'LIGHTNING':
            _write_lightning_polyline(msp, pts, closed, aci, layer_name)
        else:
            deg = 3 if len(pts) >= 4 else (2 if len(pts) == 3 else 1)
            flags = 1 if closed else 0
            fit_pts = [Vec2(pt[0], fy(pt[1])) for pt in pts]
            if lt == 'DASHED':
                dxf_attribs['linetype'] = 'DASHED'
            # Compute start/end tangents like Scheda does
            sp = msp.add_spline(
                fit_points=fit_pts,
                degree=deg,
                dxfattribs=dxf_attribs
            )
            # Set tangents
            if not closed and len(pts) >= 2:
                # Start tangent
                for si in range(1, len(pts)):
                    dx = pts[si][0] - pts[0][0]
                    dy = pts[si][1] - pts[0][1]
                    length = math.hypot(dx, dy)
                    if length > 1e-6:
                        sp.dxf.start_tangent = (dx/length, -dy/length, 0.0)
                        break
                # End tangent
                for ei in range(len(pts)-2, -1, -1):
                    dx = pts[-1][0] - pts[ei][0]
                    dy = pts[-1][1] - pts[ei][1]
                    length = math.hypot(dx, dy)
                    if length > 1e-6:
                        sp.dxf.end_tangent = (dx/length, -dy/length, 0.0)
                        break
    
    elif pt == 'number':
        text = str(p.get('value', 0))
        fs = to_f32(p.get('fontSize', 30.0))
        x = p.get('x', p.get('startX', 0))
        y = p.get('y', p.get('startY', 0))
        rot = p.get('rotation', 0.0)
        adj = to_f32(len(text) * fs) * 0.12
        msp.add_text(
            text,
            dxfattribs={
                **dxf_attribs,
                'style': _TEXT_STYLE,
                'height': fs,
                'rotation': -rot * 180.0 / math.pi if abs(rot) > 0.001 else 0.0,
            }
        ).set_placement((x + adj, fy(y)), align=TextEntityAlignment.MIDDLE_CENTER)
        if p.get('circled', False):
            # 与 Kotlin 侧一致：半径 = max(文本宽/2, 0.65*fs) * 1.15，粗体数字宽约 0.72*fs/字
            radius = max(len(text) * fs * 0.36, fs * 0.65) * 1.15
            msp.add_circle(center=(x + adj, fy(y) + fs * 0.055), radius=radius, dxfattribs=dxf_attribs)
    
    elif pt == 'text':
        text = p.get('text', '')
        fs = to_f32(p.get('fontSize', 30.0))
        x = p.get('x', p.get('startX', 0))
        y = p.get('y', p.get('startY', 0))
        rot = p.get('rotation', 0.0)
        rot_deg = -rot * 180.0 / math.pi if abs(rot) > 0.001 else 0.0
        cos_r, sin_r = math.cos(rot), math.sin(rot)
        # DXF TEXT 不支持内嵌换行：多行拆成多个 TEXT，行堆叠方向随文字旋转（模型坐标 y 向下）
        lines = text.rstrip('\n').split('\n')
        line_h = fs * 1.3
        for i, ln in enumerate(lines):
            adj = to_f32(len(ln) * fs) * 0.12
            ox = -i * line_h * sin_r
            oy = i * line_h * cos_r
            msp.add_text(
                ln,
                dxfattribs={
                    **dxf_attribs,
                    'style': _TEXT_STYLE,
                    'height': fs,
                    'rotation': rot_deg,
                }
            ).set_placement((x + adj + ox, fy(y + oy)), align=TextEntityAlignment.MIDDLE_CENTER)
    
    elif pt == 'range':
        _write_range(msp, p, aci, layer_name)
    
    elif pt == 'circle':
        if 'centerX' in p: cx, cy = p['centerX'], p['centerY']
        else: cx, cy = (p['startX']+p['endX'])/2, (p['startY']+p['endY'])/2
        rx = p.get('radiusX', abs(p.get('endX',0)-p.get('startX',0))/2)
        ry = p.get('radiusY', abs(p.get('endY',0)-p.get('startY',0))/2)
        if lt == 'DASHED':
            dxf_attribs['linetype'] = 'DASHED'
        if abs(rx-ry) < 0.001:
            msp.add_circle((cx, fy(cy)), (rx+ry)/2, dxfattribs=dxf_attribs)
        else:
            rot = p.get('rotation', 0.0)
            cr, sr = math.cos(rot), math.sin(rot)
            mdx, mdy = (rx*cr, rx*sr) if rx >= ry else (-ry*sr, ry*cr)
            msp.add_ellipse((cx, fy(cy)), (mdx, fy(mdy)),
                          ratio=ry/rx if rx>=ry else rx/ry, dxfattribs=dxf_attribs)

    else:
        _WARNINGS.append(f"unknown primitive type: {pt}")


def _lightning_x(msp, x1, y1, x2, y2, aci, layer):
    dx, dy = x2 - x1, y2 - y1
    length = math.hypot(dx, dy)
    if length < 1.0: return
    xsize, c45, s45 = 16.0, 0.7071067812, 0.7071067812
    n = max(2, int(length / 120.0))
    for k in range(1, n + 1):
        t = k / (n + 1)
        mx, my = x1 + t * dx, y1 + t * dy
        d1x = (dx * c45 - dy * s45) / length * xsize
        d1y = (dx * s45 + dy * c45) / length * xsize
        d2x = (dx * c45 + dy * s45) / length * xsize
        d2y = (-dx * s45 + dy * c45) / length * xsize
        attr = {'layer': layer, 'color': aci}
        msp.add_line((mx-d1x, fy(my-d1y)), (mx+d1x, fy(my+d1y)), dxfattribs=attr)
        msp.add_line((mx-d2x, fy(my-d2y)), (mx+d2x, fy(my+d2y)), dxfattribs=attr)


def _write_lightning_polyline(msp, pts, closed, aci, layer):
    # Catmull-Rom smooth path
    sampled = _catmull_rom(pts, closed, 8)
    attr = {'layer': layer, 'color': aci}
    for i in range(len(sampled)-1):
        msp.add_line((sampled[i][0], fy(sampled[i][1])),
                    (sampled[i+1][0], fy(sampled[i+1][1])), dxfattribs=attr)
    
    # X marks along original path（闭合路径计入末点→首点的闭合段）
    seg_pts = list(pts) + [pts[0]] if closed else pts
    seg_lens = [math.hypot(seg_pts[i+1][0]-seg_pts[i][0], seg_pts[i+1][1]-seg_pts[i][1])
                for i in range(len(seg_pts)-1)]
    total_len = sum(seg_lens)
    if total_len <= 0: return
    n = max(2, int(total_len / 120.0))
    target = total_len / n
    accum, seg_idx = 0.0, 0
    xsize, c45, s45 = 16.0, 0.7071067812, 0.7071067812
    for k in range(1, n):
        wanted = k * target
        # 跳过零长段（触屏采样常产生重合点），避免除零
        while seg_idx < len(seg_lens) and (seg_lens[seg_idx] <= 1e-12 or accum + seg_lens[seg_idx] < wanted):
            accum += seg_lens[seg_idx]; seg_idx += 1
        if seg_idx >= len(seg_lens): break
        t = (wanted - accum) / seg_lens[seg_idx]
        sx = seg_pts[seg_idx][0] + (seg_pts[seg_idx+1][0]-seg_pts[seg_idx][0]) * t
        sy = seg_pts[seg_idx][1] + (seg_pts[seg_idx+1][1]-seg_pts[seg_idx][1]) * t
        pdx, pdy = seg_pts[seg_idx+1][0]-seg_pts[seg_idx][0], seg_pts[seg_idx+1][1]-seg_pts[seg_idx][1]
        pl = math.hypot(pdx, pdy)
        if pl > 0.01:
            udx, udy = pdx/pl, pdy/pl
            d1x = (udx*c45 - udy*s45) * xsize
            d1y = (udx*s45 + udy*c45) * xsize
            d2x = (udx*c45 + udy*s45) * xsize
            d2y = (-udx*s45 + udy*c45) * xsize
            msp.add_line((sx-d1x, fy(sy-d1y)), (sx+d1x, fy(sy+d1y)), dxfattribs=attr)
            msp.add_line((sx-d2x, fy(sy-d2y)), (sx+d2x, fy(sy+d2y)), dxfattribs=attr)


def _catmull_rom(points, closed, samples=8):
    n = len(points)
    if n < 2: return list(points)
    result = []
    total = n if closed else n - 1
    def cr(v0, v1, v2, v3, t):
        t2, t3 = t*t, t*t*t
        return 0.5*((2*v1)+(-v0+v2)*t+(2*v0-5*v1+4*v2-v3)*t2+(-v0+3*v1-3*v2+v3)*t3)
    for i in range(total):
        p0 = points[(i-1+n)%n] if closed else points[max(0,i-1)]
        p1 = points[i]
        p2 = points[(i+1)%n] if closed else points[min(n-1,i+1)]
        p3 = points[(i+2)%n] if closed else points[min(n-1,i+2)]
        for t in range(samples):
            f = t / samples
            result.append((cr(p0[0],p1[0],p2[0],p3[0],f),
                          cr(p0[1],p1[1],p2[1],p3[1],f)))
    result.append(points[0 if closed else n-1])
    return result


def _write_range(msp, p, aci, layer):
    """区间数字：直接消费 Kotlin RangeLabelLayout 纯函数预计算的放置信息（textLayout），
    与画布渲染（含横屏模式）完全一致，不再自行推算角度/布局。
    texts 每项含 text/x/y/angle：angle 为 App 屏幕顺时针弧度，y 为 App 世界坐标（Y 向下），
    写出时 angle → DXF 逆时针度数（-angle*180/π）、y → fy() 翻转，文字 MIDDLE_CENTER 对齐锚点。"""
    tl = p.get('textLayout')
    if not tl:
        _WARNINGS.append("range: missing textLayout, skipped")
        return
    attr = {'layer': layer, 'color': aci}
    arrow_angle = float(tl.get('arrowAngle', 0.0))
    half = float(tl.get('arrowHalf', 40.0))
    rev = bool(tl.get('reversed', False))
    fs = to_f32(p.get('fontSize', 30.0))
    x = float(p.get('x', p.get('startX', 0)))
    y = float(p.get('y', p.get('startY', 0)))

    # 区间线：局部 (-half,0)→(+half,0) 绕中心按 arrowAngle（屏幕顺时针）旋转，fy 翻 Y
    cosA, sinA = math.cos(arrow_angle), math.sin(arrow_angle)
    ax1x, ax1y = x - half * cosA, y - half * sinA
    ax2x, ax2y = x + half * cosA, y + half * sinA
    msp.add_line((ax1x, fy(ax1y)), (ax2x, fy(ax2y)), dxfattribs=attr)

    # 箭头：非反向在结束端（+half），反向在起始端（-half），翼公式与 RangeLabelLayout 同源：
    # 尖端朝向 dirA = arrowAngle（反向 +π），两翼 = 反方向 ±45°
    hs = max(4.0, fs * 0.3)
    tipX, tipY = (ax1x, ax1y) if rev else (ax2x, ax2y)
    dirA = arrow_angle + (math.pi if rev else 0.0)
    cosD, sinD = math.cos(dirA), math.sin(dirA)
    e1x = tipX + hs * (sinD - cosD); e1y = tipY - hs * (sinD + cosD)
    e2x = tipX - hs * (sinD + cosD); e2y = tipY + hs * (cosD - sinD)
    msp.add_line((tipX, fy(tipY)), (e1x, fy(e1y)), dxfattribs=attr)
    msp.add_line((tipX, fy(tipY)), (e2x, fy(e2y)), dxfattribs=attr)

    # 文字段（两端数字；中间数字当前模型无字段，textLayout 亦不含）
    for t in tl.get('texts', []):
        txt = str(t.get('text', ''))
        tx = float(t.get('x', x)); ty = float(t.get('y', y))
        ang = float(t.get('angle', 0.0))
        rot_deg = -ang * 180.0 / math.pi if abs(ang) > 0.001 else 0.0
        te = msp.add_text(txt, dxfattribs={**attr, 'style': _TEXT_STYLE, 'height': fs,
                                           'rotation': rot_deg})
        te.set_placement((tx, fy(ty)), align=TextEntityAlignment.MIDDLE_CENTER)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: scheda2dxf.py <input.scheda> <output.dxf>", file=sys.stderr)
        sys.exit(1)
    scheda_to_dxf(sys.argv[1], sys.argv[2])


def _write_images(doc, msp, images, name_map):
    """参考图片：IMAGE 实体（外部文件引用，IMAGEDEF 存相对文件名，图片须与 DXF 同目录）。
    App 世界 y 向下、角度为屏幕顺时针；DXF y 向上（fy 翻转）、CCW 为正。
    先于基元写入 → CAD 实体顺序即叠放次序，图片在最底层，与 App 一致。
    透明度：IMAGE 的 fade 属性（组码 283，0-100，R2000 原生）即 CAD 里的图片褪色度/
    透明度（AutoCAD IMAGEADJUST），fade = (1-alpha)*100，白底下视觉与 App 半透明一致。"""
    if not images:
        return
    doc.set_raster_variables(frame=0, quality=1, units='m')
    for img in images:
        try:
            fn = img['filename']
            pw = max(int(img.get('pixelWidth', 0)), 1)
            ph = max(int(img.get('pixelHeight', 0)), 1)
            cx = float(img['cx']); cy = float(img['cy'])
            w = float(img['w']); h = float(img['h'])
            rot = float(img.get('rotationDeg', 0.0))
            alpha = max(0.0, min(float(img.get('alpha', 1.0)), 1.0))
            fade = int(round((1.0 - alpha) * 100))
            layer = name_map.get(img.get('layerId', 0), '0')
            # 注意：App 随 Chaquopy 打包的是 ezdxf 1.4.x，参数名必须用 size_in_pixel（旧版曾用 size_in_px）
            image_def = doc.add_image_def(filename=fn, size_in_pixel=(pw, ph))
            # 未旋转左下角（DXF 坐标）；App 绕中心转，DXF 绕插入点转 →
            # 旋转角取负（屏幕顺时针 = DXF 顺时针 = 负 CCW），插入点绕中心同步旋转
            lx, ly = cx - w / 2.0, fy(cy + h / 2.0)
            dxf_rot = -rot
            if abs(dxf_rot) > 1e-9:
                a = math.radians(dxf_rot)
                ca, sa = math.cos(a), math.sin(a)
                ccx, ccy = cx, fy(cy)
                ddx, ddy = lx - ccx, ly - ccy
                lx = ccx + ddx * ca - ddy * sa
                ly = ccy + ddx * sa + ddy * ca
            img_entity = msp.add_image(image_def, insert=(lx, ly), size_in_units=(w, h),
                          rotation=dxf_rot, dxfattribs={'layer': layer})
            img_entity.dxf.fade = fade
            # 裁剪边界：与图片占位一致的 WCS 矩形（绕图片中心旋转后的四角），保证 CAD 显示与画布一致
            a = math.radians(dxf_rot) if abs(dxf_rot) > 1e-9 else 0.0
            ca, sa = math.cos(a), math.sin(a)
            ccx, ccy = cx, fy(cy)
            def rp(ox, oy):
                return (ccx + ox * ca - oy * sa, ccy + ox * sa + oy * ca)
            corners = [rp(-w/2, -h/2), rp(w/2, -h/2), rp(w/2, h/2), rp(-w/2, h/2)]
            img_entity.set_boundary_path(corners)
        except Exception as e:
            _WARNINGS.append(f"image: {e}")
            print(f"  Skipping image: {e}", file=sys.stderr)


def scheda_json_to_dxf(json_str: str) -> bytes:
    """Convert .scheda JSON string to DXF bytes. Called from Kotlin via Chaquopy."""
    _WARNINGS.clear()
    scheda = json.loads(json_str)
    primitives = scheda.get('primitives', [])
    images = scheda.get('images', [])
    layers_data = scheda.get('layers', [{'id': 0, 'name': '图层0', 'isVisible': True, 'color': -16777216}])

    doc = ezdxf.new("AC1015")
    _ensure_text_style(doc)
    # LAYER 表（含名称净化与隐藏层 OFF 状态）
    name_map = _build_layer_table(doc, layers_data)
    doc.encoding = 'gbk'
    msp = doc.modelspace()

    # 参考图片先写入（实体顺序 = 叠放次序，图片垫底）
    _write_images(doc, msp, images, name_map)

    for p in primitives:
        try:
            _write_primitive(msp, p, doc, name_map)
        except Exception as e:
            _WARNINGS.append(f"{p.get('type', '?')}: {e}")
            print(f"  Skipping entity: {e}", file=sys.stderr)

    buf = io.StringIO()
    doc.write(buf)
    return buf.getvalue().encode(doc.encoding, errors='dxfreplace')
