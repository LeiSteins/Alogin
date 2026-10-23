"""Convert the existing Android launcher asset; requires Pillow only to regenerate."""
from pathlib import Path

from PIL import Image


def main():
    client = Path(__file__).resolve().parents[1]
    source = client.parent / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp"
    target = client / "resources/alogin.ico"
    sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 192, 256]
    with Image.open(source) as image:
        # Preserve the Android artwork, alpha and proportions. ICO stores separate
        # raster sizes for Explorer, the title bar, taskbar and system tray.
        icon = image.convert("RGBA").resize((256, 256), Image.Resampling.LANCZOS)
        icon.save(target, format="ICO", sizes=[(size, size) for size in sizes])


if __name__ == "__main__":
    main()
