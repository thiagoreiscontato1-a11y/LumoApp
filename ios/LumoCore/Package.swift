// swift-tools-version:5.9
import PackageDescription

// Núcleo do Lumo iOS sem dependências de UIKit/SwiftUI. Os mesmos arquivos
// também são compilados diretamente no target do app (Lumo.xcodeproj), então
// aqui ficam apenas algoritmos e protocolos que rodam em iOS, macOS e Linux.
let package = Package(
    name: "LumoCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "LumoCore", targets: ["LumoCore"])],
    targets: [
        .target(name: "LumoCore"),
        .testTarget(name: "LumoCoreTests", dependencies: ["LumoCore"]),
    ]
)
