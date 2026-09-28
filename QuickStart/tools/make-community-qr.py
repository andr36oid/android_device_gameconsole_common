# Run from QuickStart/ (needs the qrcode and pillow Python packages).
# QR for https://t.me/andr36oid with a small Telegram-style icon in the middle.
# High error correction, so the covered middle (under 10% of the area) still scans.
import qrcode
from PIL import Image, ImageDraw
URL = "https://t.me/andr36oid"
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_H, box_size=8, border=3)
qr.add_data(URL); qr.make(fit=True)
img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
W = img.size[0]
S = 4  # draw the icon big, then scale down for smooth edges
d = int(W * 0.22) * S
icon = Image.new("RGBA", (d, d), (0, 0, 0, 0))
g = ImageDraw.Draw(icon)
g.ellipse((0, 0, d - 1, d - 1), fill=(255, 255, 255, 255))          # white ring
m = int(d * 0.06)
g.ellipse((m, m, d - 1 - m, d - 1 - m), fill=(0x27, 0xA7, 0xE7, 255))  # Telegram blue
def p(x, y): return (m + (d - 2 * m) * x, m + (d - 2 * m) * y)
# Paper plane: body, and the darker fold
g.polygon([p(0.22, 0.50), p(0.76, 0.28), p(0.66, 0.74), p(0.50, 0.62), p(0.42, 0.72), p(0.42, 0.58)],
          fill=(255, 255, 255, 255))
g.polygon([p(0.42, 0.58), p(0.70, 0.36), p(0.46, 0.60), p(0.42, 0.72)], fill=(0xC8, 0xDA, 0xEA, 255))
icon = icon.resize((d // S, d // S), Image.LANCZOS)
o = (W - icon.size[0]) // 2
img.paste(icon, (o, o), icon)
img.save("res/drawable-nodpi/community_qr.png", optimize=True)
print(img.size)
