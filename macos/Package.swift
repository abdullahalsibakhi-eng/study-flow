// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "StudyFlow", platforms: [.macOS(.v14)], products: [.executable(name: "StudyFlow", targets: ["StudyFlow"])], targets: [.executableTarget(name: "StudyFlow"), .executableTarget(name: "CredentialHelper")])
