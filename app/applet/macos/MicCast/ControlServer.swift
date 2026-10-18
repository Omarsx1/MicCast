import Foundation
import Network
import CommonCrypto

public class ControlServer: ObservableObject {
    public static let shared = ControlServer()

    @Published public var currentPin: String = ""
    @Published public var currentSessionId: UInt32 = 0
    @Published public var isClientConnected: Bool = false
    @Published public var connectedClientHost: String = ""

    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private var failedAttempts: Int = 0
    private var lockoutUntil: Date?

    public init() {
        generateNewPin()
    }

    public func generateNewPin() {
        let pin = String(format: "%06d", arc4random_uniform(1000000))
        DispatchQueue.main.async {
            self.currentPin = pin
            self.currentSessionId = arc4random_uniform(9000000) + 1000000
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

    private func handleIncomingConnection(_ connection: NWConnection) {
        if let lockout = lockoutUntil, Date() < lockout {
            print("[CONTROL SERVER] Connection rejected due to brute-force lockout")
            connection.cancel()
            return
        }

        connection.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.startHandshake(on: connection)
            }
        }
        connection.start(queue: .main)
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
            // 3. Receive client response: Magic (4B) + Version (1B) + HMAC (32B) = 37 bytes
            self?.receiveClientResponse(on: connection, nonce: nonce)
        })
    }

    private func receiveClientResponse(on connection: NWConnection, nonce: [UInt8]) {
        connection.receive(minimumIncompleteLength: 37, maximumLength: 37) { [weak self] data, _, _, error in
            guard let self = self, let data = data, data.count >= 37, error == nil else {
                connection.cancel()
                return
            }

            let magic = data.subdata(in: 0..<4)
            let version = data[4]
            let clientHmac = data.subdata(in: 5..<37)

            let expectedMagic = Data([0x4D, 0x43, 0x53, 0x43]) // 'MCSC'
            guard magic == expectedMagic, version == 0x02 else {
                connection.cancel()
                return
            }

            // Verify HMAC-SHA256(PIN, nonce)
            let pinData = self.currentPin.data(using: .utf8)!
            var expectedHmac = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
            nonce.withUnsafeBytes { noncePtr in
                pinData.withUnsafeBytes { pinPtr in
                    CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA256), pinPtr.baseAddress, pinData.count, noncePtr.baseAddress, nonce.count, &expectedHmac)
                }
            }

            if clientHmac == Data(expectedHmac) {
                print("[CONTROL SERVER] ✅ PIN authentication SUCCESSFUL")
                self.failedAttempts = 0

                // Respond STATUS_OK (0x00) + Session ID (4 bytes)
                var response = Data([0x00])
                var sessionIdBigEndian = self.currentSessionId.bigEndian
                response.append(Data(bytes: &sessionIdBigEndian, count: 4))

                connection.send(content: response, completion: .contentProcessed { _ in
                    self.activeConnection = connection
                    DispatchQueue.main.async {
                        self.isClientConnected = true
                        if case let .hostPort(host, _) = connection.endpoint {
                            self.connectedClientHost = "\(host)"
                        }
                    }
                    self.listenForPing(on: connection)
                })
            } else {
                print("[CONTROL SERVER] ❌ PIN authentication FAILED")
                self.failedAttempts += 1
                if self.failedAttempts >= 5 {
                    self.lockoutUntil = Date().addingTimeInterval(60) // 1 minute lockout
                    print("[CONTROL SERVER] 🔒 Too many failures. Locked out for 60 seconds.")
                }
                connection.send(content: Data([0x01]), completion: .contentProcessed { _ in
                    connection.cancel()
                })
            }
        }
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
        isClientConnected = false
    }
}
