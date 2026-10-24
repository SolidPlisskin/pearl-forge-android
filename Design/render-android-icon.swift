// Renders the Pearl Forge Android launcher icon (432×432, the full 108dp adaptive-icon layer at xxxhdpi), from the iPhone icon's artwork.
// Run from the Design folder:  xcrun swiftc -O -o /tmp/render render-android-icon.swift && /tmp/render ../app/src/main/res/drawable-nodpi/ic_launcher_art.png
// It also writes preview-192.png next to where it runs.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Pearl Forge app icon: a luminous pearl above a red record button, on deep
// teal, with faint rings for the voice. 1024×1024, fully opaque.
let size = 432
let s = CGFloat(size)
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}
func gradient(_ stops: [(CGFloat, CGColor)]) -> CGGradient {
    CGGradient(colorsSpace: space, colors: stops.map(\.1) as CFArray, locations: stops.map(\.0))!
}
func radial(_ g: CGGradient, at c: CGPoint, radius: CGFloat, from start: CGPoint? = nil) {
    ctx.drawRadialGradient(g, startCenter: start ?? c, startRadius: 0, endCenter: c, endRadius: radius,
                           options: [.drawsAfterEndLocation])
}

// 1. Background: deep teal fading to near-black at the bottom.
ctx.drawLinearGradient(gradient([(0, color(0x02070B)), (0.42, color(0x082A36)), (1, color(0x135A6E))]),
                       start: CGPoint(x: 0, y: 0), end: CGPoint(x: 0, y: s), options: [])

// Android: everything after the background is shrunk toward the centre, so it fits inside the
// launcher's round or squircle mask.
ctx.translateBy(x: s / 2, y: s / 2)
ctx.scaleBy(x: 0.64, y: 0.64)
ctx.translateBy(x: -s / 2, y: -s / 2)

// 2. A soft red light around where the record button sits.
let button = CGPoint(x: s / 2, y: s * 0.155)
radial(gradient([(0, color(0xFF3B30, 0.42)), (0.45, color(0xD0201A, 0.14)), (1, color(0x9A1410, 0))]),
       at: button, radius: s * 0.26)

// 3. Voice rings around the pearl.
let center = CGPoint(x: s / 2, y: s * 0.585)
let r = s * 0.24
for (radius, alpha, width) in [(r * 1.36, 0.13, s * 0.010), (r * 1.66, 0.075, s * 0.008), (r * 1.96, 0.04, s * 0.006)] {
    ctx.setStrokeColor(color(0xE8F4F7, alpha))
    ctx.setLineWidth(width)
    ctx.strokeEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
}

// 4. A soft halo so the pearl lifts off the background.
radial(gradient([(0.0, color(0xFFF3E0, 0.0)), (0.72, color(0xFFE7C2, 0.0)), (0.80, color(0xFFE7C2, 0.16)), (1, color(0xFFE7C2, 0))]),
       at: center, radius: r * 1.28)

// 5. The pearl.
let pearlRect = CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)
ctx.saveGState()
ctx.addEllipse(in: pearlRect)
ctx.clip()
// Body: light from the upper left, cooling toward the edge.
radial(gradient([(0, color(0xFFFFFF)), (0.18, color(0xFDF8F1)), (0.45, color(0xEDE5EC)), (0.72, color(0xC9CBD8)), (0.92, color(0x99A1B4)), (1, color(0x7A8398))]),
       at: center, radius: r, from: CGPoint(x: center.x - r * 0.34, y: center.y + r * 0.38))
// Iridescent sheen: faint pink and aqua across the surface.
ctx.setBlendMode(.softLight)
ctx.drawLinearGradient(gradient([(0, color(0xFF9EC4, 0.55)), (0.5, color(0xFFFFFF, 0.0)), (1, color(0x7FE3E6, 0.55))]),
                       start: CGPoint(x: center.x - r, y: center.y + r), end: CGPoint(x: center.x + r, y: center.y - r), options: [])
radial(gradient([(0, color(0xFF8FB8, 0.0)), (0.62, color(0xFF8FB8, 0.0)), (0.92, color(0xFF8FB8, 0.55)), (1, color(0xFF8FB8, 0.2))]),
       at: CGPoint(x: center.x + r * 0.18, y: center.y), radius: r * 1.02)
radial(gradient([(0, color(0x6FE0D8, 0.0)), (0.66, color(0x6FE0D8, 0.0)), (0.93, color(0x6FE0D8, 0.5)), (1, color(0x6FE0D8, 0.2))]),
       at: CGPoint(x: center.x - r * 0.20, y: center.y + r * 0.05), radius: r * 1.02)
ctx.setBlendMode(.normal)
// The record button's red light faintly catching the underside.
radial(gradient([(0, color(0xFF5A50, 0.45)), (0.55, color(0xFF4A40, 0.14)), (1, color(0xFF4A40, 0))]),
       at: CGPoint(x: center.x, y: center.y - r * 1.2), radius: r * 1.0)
// Edge shading for roundness.
radial(gradient([(0, color(0x1A2433, 0)), (0.82, color(0x1A2433, 0)), (1, color(0x1A2433, 0.30))]), at: center, radius: r)
ctx.restoreGState()

// Specular highlight.
ctx.saveGState()
ctx.translateBy(x: center.x - r * 0.36, y: center.y + r * 0.42)
ctx.rotate(by: .pi / 6)
ctx.scaleBy(x: 1.0, y: 0.62)
radial(gradient([(0, color(0xFFFFFF, 1.0)), (0.30, color(0xFFFFFF, 0.85)), (0.60, color(0xFFFFFF, 0.25)), (1, color(0xFFFFFF, 0))]),
       at: .zero, radius: r * 0.30)
ctx.restoreGState()
// A small second glint.
radial(gradient([(0, color(0xFFFFFF, 0.55)), (1, color(0xFFFFFF, 0))]),
       at: CGPoint(x: center.x + r * 0.42, y: center.y - r * 0.38), radius: r * 0.12)

// 6. The record button: a white ring around a glossy red circle.
let ringRadius = s * 0.085
ctx.setStrokeColor(color(0xFFFFFF, 0.94))
ctx.setLineWidth(s * 0.013)
ctx.strokeEllipse(in: CGRect(x: button.x - ringRadius, y: button.y - ringRadius, width: ringRadius * 2, height: ringRadius * 2))
let dot = s * 0.064
ctx.saveGState()
ctx.addEllipse(in: CGRect(x: button.x - dot, y: button.y - dot, width: dot * 2, height: dot * 2))
ctx.clip()
ctx.drawLinearGradient(gradient([(0, color(0xC81E17)), (1, color(0xFF4D42))]),
                       start: CGPoint(x: button.x, y: button.y - dot), end: CGPoint(x: button.x, y: button.y + dot), options: [])
radial(gradient([(0, color(0xFFFFFF, 0.38)), (1, color(0xFFFFFF, 0))]),
       at: CGPoint(x: button.x - dot * 0.3, y: button.y + dot * 0.45), radius: dot * 0.65)
ctx.restoreGState()

// Save.
let image = ctx.makeImage()!
func write(_ image: CGImage, to path: String) {
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}
write(image, to: CommandLine.arguments[1])

// Small previews, as the icon appears on the home screen and in Settings.
for px in [192] {
    let small = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    small.interpolationQuality = .high
    small.draw(image, in: CGRect(x: 0, y: 0, width: px, height: px))
    write(small.makeImage()!, to: "preview-\(px).png")
}
