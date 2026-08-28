#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_dxf_export.py — DXF 导出逻辑的本地模拟验证脚本（配合 pip 安装的 ezdxf 运行）。

验证范围（对应本次修复的三个问题）：
  1. 区间数字朝向纯函数（Kotlin RangeLabelLayout）的输出 → 预计算 textLayout 写入 JSON，
     Python 端直接消费，校验 DXF TEXT 旋转角/对齐（组 72/73=MIDDLE_CENTER、第二对齐点组 11）
     符合竖屏/横屏预期，与画布渲染一致。
  2. 参考图导出完整性：IMAGEDEF（挂 ACAD_IMAGE_DICT）+ IMAGE 实体（340 软指针指向 IMAGEDEF）+
     裁剪边界；IMAGEDEF 路径为相对文件名（非手机绝对路径）；DXF 版本 R2000（AC1015）。
  最后跑 ezdxf audit，全部通过才返回 0。

运行方式：
  pip install ezdxf
  python docs/verify_dxf_export.py
"""
import io
import json
import math
import os
import sys

import ezdxf
from ezdxf import recover

APP_PY = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      "..", "app", "src", "main", "python"))

TOL = 1e-3


def rot(v):
    return round(float(v), 3)


def approx(a, b, tol=TOL):
    return abs(float(a) - float(b)) < tol


# ═══ 模拟 Kotlin DxfExporter 的 JSON 输出 ═══

def build_range_json(rotation, is_landscape, x=0.0, y=0.0, start=1, end=2,
                     fs=30.0, span=1.0, reversed_=False):
    """按 Kotlin RangeLabelLayout 纯函数逻辑生成 range 图元 JSON（含 textLayout）。"""
    half = max(40.0 * span, 10.0)
    d = half + fs
    arrow_angle = rotation + (math.pi / 2.0 if is_landscape else 0.0)
    text_angle = (math.pi / 2.0 if is_landscape else 0.0)

    def rotate_local(lx, ly):
        ca, sa = math.cos(arrow_angle), math.sin(arrow_angle)
        return (x + lx * ca - ly * sa, y + lx * sa + ly * ca)

    sl = d if reversed_ else -d
    el = -d if reversed_ else d
    sx, sy = rotate_local(sl, 0.0)
    ex, ey = rotate_local(el, 0.0)
    return {
        "type": "range", "layerId": 0, "color": -16777216, "strokeWidth": 2,
        "lineType": "SOLID",
        "startValue": start, "endValue": end, "fontSize": fs,
        "x": x, "y": y, "rotation": rotation, "reversed": reversed_,
        "horizontalOnly": abs(rotation) < 1e-3, "arrowSpan": span,
        "textLayout": {
            "arrowAngle": arrow_angle,
            "arrowHalf": half,
            "reversed": reversed_,
            "texts": [
                {
                    "text": str(end if reversed_ else start),
                    "x": sx, "y": sy, "angle": text_angle,
                },
                {
                    "text": str(start if reversed_ else end),
                    "x": ex, "y": ey, "angle": text_angle,
                },
            ],
        },
    }


def build_image_json():
    return [
        {"filename": "image_ref.jpg", "cx": 100.0, "cy": 50.0, "w": 200.0, "h": 100.0,
         "rotationDeg": 30.0, "layerId": 0, "pixelWidth": 100, "pixelHeight": 50}
    ]


def build_scheda_json(ranges, images):
    return {
        "primitives": ranges,
        "layers": [{"id": 0, "name": "图层0", "isVisible": True, "color": -16777216}],
        "images": images,
    }


# ═══ 验证入口 ═══

def main():
    sys.path.insert(0, APP_PY)
    import scheda_dxf_export as m  # 实际使用 App 内的导出引擎

    failures = []

    def check(name, cond, detail=""):
        if cond:
            print(f"  [PASS] {name}")
        else:
            failures.append(name)
            print(f"  [FAIL] {name} {detail}")

    # ── 竖屏 + 横屏两种输入的区间数字 ──
    portrait_ranges = [build_range_json(0.0, False), build_range_json(math.pi / 2, False)]
    landscape_ranges = [build_range_json(0.0, True), build_range_json(math.pi / 2, True)]
    images = build_image_json()

    dxf_bytes = m.scheda_json_to_dxf(json.dumps(build_scheda_json(landscape_ranges, images)))
    if m.get_last_warnings():
        failures.append("export_warnings")
        print(f"  [FAIL] 导出告警: {m.get_last_warnings()}")

    doc, auditor = recover.read(io.BytesIO(dxf_bytes))

    # ── ezdxf audit 通过 ──
    check("ezdxf audit 无错误", not auditor.has_errors,
          f"errors={[str(e) for e in auditor.errors]}")

    # ── DXF 版本 R2000+ ──
    check("DXF 版本 AC1015", doc.dxfversion == "AC1015", f"version={doc.dxfversion}")

    # ── 区间数字 TEXT：旋转角 / 对齐组 ──
    msp = doc.modelspace()
    texts = sorted(msp.query("TEXT"), key=lambda t: (t.dxf.text, t.dxf.insert.x, t.dxf.insert.y))
    check("TEXT 数量==4（横屏 2 个区间 × 2 端数字）", len(texts) == 4, f"n={len(texts)}")
    for t in texts:
        halign = t.dxf.get("halign", 0)
        valign = t.dxf.get("valign", 0)
        # MIDDLE_CENTER = halign 1 (CENTER) + valign 2 (MIDDLE)，对应组 72/73
        check(f"TEXT({t.dxf.text}) 对齐组 72/73=MIDDLE_CENTER", halign == 1 and valign == 2,
              f"72={halign} 73={valign}")
        check(f"TEXT({t.dxf.text}) 旋转角 == -90°（横屏，屏幕顺时针+90°→DXF 逆时针−90°）",
              approx(t.dxf.rotation, -90.0), f"rotation={t.dxf.rotation}")

    # ── 参考图 IMAGE 三件套 ──
    images_ent = list(msp.query("IMAGE"))
    check("IMAGE 实体存在", len(images_ent) == 1, f"n={len(images_ent)}")
    if images_ent:
        img = images_ent[0]
        idef_handle = img.dxf.image_def_handle
        idef = doc.entitydb.get(idef_handle) if idef_handle else None
        check("IMAGE 340 软指针完整（指向 IMAGEDEF）",
              idef is not None and idef.dxftype() == "IMAGEDEF",
              f"handle={idef_handle}")
        if idef is not None:
            fname = idef.dxf.filename
            check("IMAGEDEF 路径为相对文件名（非手机绝对路径）",
                  fname == "image_ref.jpg" and not os.path.isabs(fname),
                  f"filename={fname}")
        check("IMAGE 启用裁剪边界（clipping=1, boundary_type=2 矩形, 点数≥5）",
              int(img.dxf.clipping) == 1 and int(img.dxf.clipping_boundary_type) == 2
              and int(img.dxf.count_boundary_points) >= 5,
              f"clip={img.dxf.clipping} btype={img.dxf.clipping_boundary_type} "
              f"npts={img.dxf.count_boundary_points}")
        # 中心校验：App 旋转参考图是绕图片中心，导出后图片中心仍须落在 (cx, fy(cy))
        half_px_w = img.dxf.image_size[0] / 2.0
        half_px_h = img.dxf.image_size[1] / 2.0
        center = img.dxf.insert + img.dxf.u_pixel * half_px_w + img.dxf.v_pixel * half_px_h
        check("图片中心 == (cx, fy(cy))",
              approx(center.x, 100.0) and approx(center.y, -50.0),
              f"center=({rot(center.x)},{rot(center.y)})")

    # ── ACAD_IMAGE_DICT（IMAGEDEF 挂 named object dictionary） ──
    dict_ok = False
    try:
        dict_ok = doc.rootdict.get("ACAD_IMAGE_DICT") is not None
    except Exception:
        dict_ok = False
    check("ACAD_IMAGE_DICT 存在于 named object dictionary", dict_ok)

    # ── 竖屏输入的 TEXT 校验（旋转角应为 0） ──
    b2 = m.scheda_json_to_dxf(json.dumps(build_scheda_json(portrait_ranges, [])))
    doc2, auditor2 = recover.read(io.BytesIO(b2))
    check("竖屏：audit 无错误", not auditor2.has_errors)
    t2 = sorted(doc2.modelspace().query("TEXT"),
                key=lambda t: (t.dxf.text, t.dxf.insert.x, t.dxf.insert.y))
    check("竖屏：TEXT 数量==4", len(t2) == 4, f"n={len(t2)}")
    for t in t2:
        check(f"竖屏 TEXT({t.dxf.text}) 旋转角==0", approx(t.dxf.rotation, 0.0),
              f"rotation={t.dxf.rotation}")

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("ALL DXF VERIFY CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())