import Foundation
import Security

/// A device that completed PIN pairing and holds a long-lived token.
public struct PairedDevice: Identifiable, Codable, Equatable {
    public let id: String          // stable client device ID (UUID string)
    public var name: String        // human readable, sent by the client
    public var tokenHex: String    // 32 bytes, hex encoded
    public var pairedAt: Date
}

/// Registry of paired devices, stored as a 0600 JSON file under Application Support.
///
/// Deliberately not the Keychain: the app is ad-hoc signed and re-signed on every
/// build, so the item ACL would break (or prompt) after each rebuild. The file holds
/// only per-device random tokens and is readable by the owning user alone.
public final class PairingStore {
    public static let shared = PairingStore()

    private let fileName = "paired-devices.json"

    private init() {}

    private var directoryURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return base.appendingPathComponent("MicCast", isDirectory: true)
    }

    private var fileURL: URL {
        directoryURL.appendingPathComponent(fileName)
    }

    public func load() -> [PairedDevice] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([PairedDevice].self, from: data)) ?? []
    }

    public func save(_ devices: [PairedDevice]) {
        guard let data = try? JSONEncoder().encode(devices) else {
            print("[PAIRING] Could not encode device registry")
            return
        }

        do {
            try FileManager.default.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
            try data.write(to: fileURL, options: .atomic)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600],
                ofItemAtPath: fileURL.path
            )
        } catch {
            print("[PAIRING] Could not persist device registry: \(error.localizedDescription)")
        }
    }

    public func token(for deviceId: String) -> Data? {
        guard let device = load().first(where: { $0.id == deviceId }) else { return nil }
        return Data(hexString: device.tokenHex)
    }

    public func upsert(_ device: PairedDevice) {
        var devices = load()
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = device
        } else {
            devices.append(device)
        }
        save(devices)
    }

    public func remove(deviceId: String) {
        save(load().filter { $0.id != deviceId })
    }

    // MARK: - Helpers

    public static func randomToken() -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }
}

public extension Data {
    init?(hexString: String) {
        let cleaned = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count % 2 == 0 else { return nil }

        var bytes = [UInt8]()
        bytes.reserveCapacity(cleaned.count / 2)

        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            let next = cleaned.index(index, offsetBy: 2)
            guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }

        self = Data(bytes)
    }

    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
