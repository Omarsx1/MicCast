import Foundation
import Network
import CommonCrypto
import Security

public class ControlServer: ObservableObject {
    public static let shared = ControlServer()

    @Published public var currentPin: String = ""
    @Published public var currentSessionId: UInt32 = 0
    @Published public var isClientConnected: Bool = false
    @Published public var connectedClientHost: String = ""
    @Published public var pairedDevices: [PairedDevice] = []

    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private var activeDeviceId: String?

    // Persisted so restarting the app no longer resets brute-force protection.
    private let defaults = UserDefaults.standard
    private let failedAttemptsKey = "miccast.control.failedAttempts"
    private let lockoutUntilKey = "miccast.control.lockoutUntil"
    private let maxFailures = 5
    private let lockoutSeconds: TimeInterval = 60

    private var failedAttempts: Int {
        get { defaults.integer(forKey: failedAttemptsKey) }
        set { defaults.set(newValue, forKey: failedAttemptsKey) }
    }

    private var lockoutUntil: Date? {
        get {
            let stored = defaults.double(forKey: lockoutUntilKey)
            return stored > 0 ? Date(timeIntervalSince1970: stored) : nil
        }
        set {
            if let newValue {
                defaults.set(newValue.timeIntervalSince1970, forKey: lockoutUntilKey)
            } else {
                defaults.removeObject(forKey: lockoutUntilKey)
            }
        }
    }

    public init() {
        generateNewPin()
        pairedDevices = PairingStore.shared.load()
    }

    public func generateNewPin() {
        let pin = String(format: "%06d", arc4random_uniform(1000000))
        DispatchQueue.main.async {
            self.currentPin = pin
        }
    }

    public func start(port: UInt16 = 51516) {
        do {
            let parameters = NWParameters.tcp
            // Advertise via Bonjour mDNS so Android discovers it automatically
            let serviceName = Host.current().localizedName ?? "Mac"
            let service = NWListener.Service(name: serviceName, type: "_miccast._tcp")

            let nwListener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
            nwListener.service = service

            nwListener.newConnectionHandler = { [weak self] connection in
                self?.handleIncomingConnection(connection)
            }

            nwListener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("[CONTROL SERVER] Ready on TCP port \(port), advertising as '\(serviceName)'")
                case .failed(let error):
                    print("[CONTROL SERVER] Failed: \(error.localizedDescription)")
                default:
                    break
                }
            }

            nwListener.start(queue: .main)
            self.listener = nwListener
        } catch {
            print("[CONTROL SERVER] Could not start listener: \(error.localizedDescription)")
        }
    }

    /// Removes the stored token and drops the session when the revoked device is connected.
    public func revokeDevice(_ deviceId: String) {
        PairingStore.shared.remove(deviceId: deviceId)
        pairedDevices = PairingStore.shared.load()
        print("[CONTROL SERVER] Device \(deviceId) revoked")

        if activeDeviceId == deviceId {
            activeConnection?.cancel()
            activeConnection = nil
            activeDeviceId = nil
            isClientConnected = false
            connectedClientHost = ""
        }
    }

    // MARK: - Connection handling

    private func handleIncomingConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.startHandshake(on: connection)
            }
        }
        connection.start(queue: .main)

        // A client that connects and never sends a valid hello must not hold the
        // connection open forever; seen in practice with a broken handshake frame.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self, weak connection] in
            guard let self, let connection else { return }
            if self.activeConnection !== connection {
                print("[CONTROL SERVER] Handshake timed out; closing connection")
                connection.cancel()
            }
        }
    }

    private func startHandshake(on connection: NWConnection) {
        // 1. Generate 16-byte random nonce
        var nonce = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &nonce)

        // 2. Send nonce to client
        connection.send(content: Data(nonce), completion: .contentProcessed { [weak self] error in
            guard error == nil else {
                connection.cancel()
                return
            }
            self?.readHelloPrefix(on: connection, nonce: nonce)
        })
    }

    private func readExact(_ count: Int, on connection: NWConnection, _ completion: @escaping (Data?) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
            guard error == nil, let data, data.count == count else {
                completion(nil)
                return
            }
            completion(data)
        }
    }

    /// Reads magic + version + the first protocol byte, then the rest of the hello.
    private func readHelloPrefix(on connection: NWConnection, nonce: [UInt8]) {
        readExact(7, on: connection) { [weak self] prefix in
            guard let self, let prefix else {
                connection.cancel()
                return
            }

            let magic = prefix.subdata(in: 0..<4)
            guard magic == Data([0x4D, 0x43, 0x53, 0x43]) else { // 'MCSC'
                connection.cancel()
                return
            }

            let version = prefix[4]

            switch version {
            case 0x02:
                // Legacy frame: magic(4) + version(1) + hmac(32); bytes 5..6 already hold hmac[0..1].
                self.readExact(30, on: connection) { rest in
                    guard let rest else {
                        connection.cancel()
                        return
                    }
                    var hmac = Data(prefix[5..<7])
                    hmac.append(rest)
                    self.authenticate(
                        on: connection,
                        nonce: nonce,
                        version: 0x02,
                        mode: 0x00,
                        deviceId: "",
                        deviceName: "",
                        hmac: hmac
                    )
                }

            case 0x03:
                let mode = prefix[5]
                let idLength = Int(prefix[6])
                guard idLength > 0, idLength <= 64 else {
                    connection.cancel()
                    return
                }

                self.readExact(idLength, on: connection) { idData in
                    guard let idData, let deviceId = String(data: idData, encoding: .utf8) else {
                        connection.cancel()
                        return
                    }

                    self.readExact(1, on: connection) { nameLengthData in
                        guard let nameLengthData else {
                            connection.cancel()
                            return
                        }
                        let nameLength = Int(nameLengthData[0])
                        guard nameLength <= 64 else {
                            connection.cancel()
                            return
                        }

                        self.readExact(nameLength + 32, on: connection) { tail in
                            guard let tail, tail.count == nameLength + 32 else {
                                connection.cancel()
                                return
                            }

                            let nameData = tail.prefix(nameLength)
                            let hmac = tail.suffix(32)
                            let deviceName = String(data: Data(nameData), encoding: .utf8) ?? "Android"

                            self.authenticate(
                                on: connection,
                                nonce: nonce,
                                version: 0x03,
                                mode: mode,
                                deviceId: deviceId,
                                deviceName: deviceName,
                                hmac: Data(hmac)
                            )
                        }
                    }
                }

            default:
                connection.cancel()
            }
        }
    }

    private func authenticate(
        on connection: NWConnection,
        nonce: [UInt8],
        version: UInt8,
        mode: UInt8,
        deviceId: String,
        deviceName: String,
        hmac: Data
    ) {
        if let lockout = lockoutUntil, Date() < lockout {
            print("[CONTROL SERVER] Connection rejected due to brute-force lockout")
            send(status: 0x03, on: connection)
            return
        }

        var key: Data?
        var issuesToken = false

        switch (version, mode) {
        case (0x02, _):
            key = currentPin.data(using: .utf8)
        case (0x03, 0x00):
            key = currentPin.data(using: .utf8)
            issuesToken = true
        case (0x03, 0x01):
            key = PairingStore.shared.token(for: deviceId)
        default:
            key = nil
        }

        guard let key, verify(hmac: hmac, key: key, nonce: nonce) else {
            print("[CONTROL SERVER] ❌ Authentication FAILED (version 0x0\(version), mode \(mode))")
            registerFailure()
            let lockedOut = lockoutUntil.map { Date() < $0 } ?? false
            send(status: lockedOut ? 0x03 : 0x01, on: connection)
            return
        }

        print("[CONTROL SERVER] ✅ Authentication SUCCESSFUL (version 0x0\(version))")
        failedAttempts = 0
        lockoutUntil = nil

        let sessionId = arc4random_uniform(9_000_000) + 1_000_000
        currentSessionId = sessionId

        var response = Data([0x00])
        var sessionBigEndian = sessionId.bigEndian
        response.append(Data(bytes: &sessionBigEndian, count: 4))

        if version == 0x03 {
            var issued = Data()
            if issuesToken {
                let token = PairingStore.randomToken()
                PairingStore.shared.upsert(
                    PairedDevice(
                        id: deviceId,
                        name: deviceName.isEmpty ? "Android" : deviceName,
                        tokenHex: token.hexString,
                        pairedAt: Date()
                    )
                )
                pairedDevices = PairingStore.shared.load()
                issued = token
                print("[CONTROL SERVER] Issued token for \(deviceName) (\(deviceId))")
            }
            response.append(Data([UInt8(issued.count)]))
            response.append(issued)
        }

        connection.send(content: response, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            guard error == nil else {
                connection.cancel()
                return
            }

            self.activeConnection = connection
            self.activeDeviceId = version == 0x03 ? deviceId : nil

            if case let .hostPort(host, _) = connection.endpoint {
                self.connectedClientHost = "\(host)"
            }
            self.isClientConnected = true

            self.listenForPing(on: connection)
        })
    }

    private func verify(hmac: Data, key: Data, nonce: [UInt8]) -> Bool {
        var expected = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        nonce.withUnsafeBytes { noncePtr in
            key.withUnsafeBytes { keyPtr in
                CCHmac(
                    CCHmacAlgorithm(kCCHmacAlgSHA256),
                    keyPtr.baseAddress,
                    key.count,
                    noncePtr.baseAddress,
                    nonce.count,
                    &expected
                )
            }
        }

        guard hmac.count == expected.count else { return false }

        var difference: UInt8 = 0
        for (received, computed) in zip(hmac, expected) {
            difference |= received ^ computed
        }
        return difference == 0
    }

    private func registerFailure() {
        failedAttempts += 1
        if failedAttempts >= maxFailures {
            lockoutUntil = Date().addingTimeInterval(lockoutSeconds)
            print("[CONTROL SERVER] 🔒 Too many failures. Locked out for \(Int(lockoutSeconds)) seconds.")
        }
    }

    private func send(status: UInt8, on connection: NWConnection) {
        connection.send(content: Data([status]), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func listenForPing(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { [weak self] data, _, _, error in
            guard let self = self, let data = data, error == nil else {
                DispatchQueue.main.async {
                    self?.isClientConnected = false
                }
                return
            }

            if data.first == 0x7F {
                // Reply with Pong byte 0x7E
                connection.send(content: Data([0x7E]), completion: .contentProcessed { _ in
                    self.listenForPing(on: connection)
                })
            } else {
                self.listenForPing(on: connection)
            }
        }
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        activeConnection?.cancel()
        activeConnection = nil
        activeDeviceId = nil
        isClientConnected = false
    }
}
