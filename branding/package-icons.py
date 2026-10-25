# Resamples the PinouTube masters in this folder into the stpinou flavor's launcher resources.
# Usage: python branding/package-icons.py   (needs Pillow)
from pathlib import Path

from PIL import Image

here = Path(__file__).resolve().parent
res = here.parent / 'smarttubetv' / 'src' / 'stpinou' / 'res'


def save(src, size, *targets, mode):
    image = Image.open(here / src).convert(mode).resize(size, Image.LANCZOS)
    for target in targets:
        path = res / target
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path, optimize=True)
        print(f'{path.relative_to(res)} {image.size} {image.mode}')


# Launcher icon (also the optional alternate launcher icon)
save('pinoutube-icon.png', (320, 320),
     'mipmap-nodpi/app_icon.png', 'mipmap-nodpi-v30/app_icon.png', 'mipmap-nodpi/app_icon_alt.png', mode='RGBA')
# Android TV home screen banner
save('pinoutube-banner.png', (640, 360), 'mipmap-nodpi/app_banner.png', mode='RGB')
# Title bar badge, used by every theme variant
save('pinoutube-mark.png', (180, 180),
     'mipmap-nodpi/app_logo.png', 'mipmap-nodpi/app_logo_semi_grey.png', 'mipmap-nodpi/app_logo_semi_red.png', mode='RGBA')
