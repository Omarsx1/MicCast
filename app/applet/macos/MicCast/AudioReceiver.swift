import Foundation
import Network

public class AudioReceiver: ObservableObject {
    public static let shared = AudioReceiver()

    @Published public var packetsReceived: UInt64 = 0
    @Published public var packetsLost: UInt64 = 0
    @Published public var isReceiving: Bool = false

    private var udpListener: NWListener?
    private var tcpUsbListener: NWListener?
    private var lastSequence: UInt16?
    private var totalPackets: UInt64 = 0
    private var warnedSessionMismatch = false

    public init() {}

    public func start(udpPort: UInt16 = 51515, tcpUsbPort: UInt16 = 51517) {
        startUdpReceiver(port: udpPort)
        startUsbTcpReceiver(port: tcpUsbPort)
    }

    private func startUdpReceiver(port: UInt16) {
        do {
            let params = NWParameters.udp
            params.allowFastOpen = true

            let serviceName = Host.current().localizedName ?? "Mac"
            let service = NWListener.Service(name: serviceName, type: "_miccast._udp")

            let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
            listener.service = service

            listener.newConnectionHandler = { [weak self] connection in
                connection.stateUpdateHandler = { state in
                    if case .ready = state {
                        self?.receiveNextUdpPacket(on: connection)
                    }
                }
                connection.start(queue: .global(qos: .userInteractive))
            }

            listener.start(queue: .main)
            self.udpListener = listener
            print("[AUDIO RECEIVER] UDP Listener ready on port \(port)")
        } catch {
            print("[AUDIO RECEIVER] Could not start UDP listener: \(error.localizedDescription)")
        }
    }

    private func receiveNextUdpPacket(on connection: NWConnection) {
        connection.receiveMessage { [weak self] content, _, _, error in
            guard let self = self, let data = content, error == nil else {
                return
            }

            self.processAudioPacket(data)
            self.receiveNextUdpPacket(on: connection)
        }
    }

    private func startUsbTcpReceiver(port: UInt16) {
        do {
            let params = NWParameters.tcp

            let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
            listener.newConnectionHandler = { [weak self] connection in
                connection.stateUpdateHandler = { state in
                    if case .ready = state {
                        print("[AUDIO RECEIVER] USB TCP Audio stream connected")
                        self?.readTcpPacketHeader(on: connection)
                    }
                }
                connection.start(queue: .global(qos: .userInteractive))
            }

            listener.start(queue: .main)
            self.tcpUsbListener = listener
            print("[AUDIO RECEIVER] USB TCP Listener ready on port \(port)")
        } catch {
            print("[AUDIO RECEIVER] Could not start USB TCP listener: \(error.localizedDescription)")
        }
    }

    private func readTcpPacketHeader(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 16, maximumLength: 16) { [weak self] headerData, _, _, error in
            guard let self = self, let header = headerData, header.count == 16, error == nil else {
                return
            }

            let payloadLength = UInt16(header[6]) << 8 | UInt16(header[7])

            // Read payload
            connection.receive(minimumIncompleteLength: Int(payloadLength), maximumLength: Int(payloadLength)) { payloadData, _, _, pError in
                guard let payload = payloadData, payload.count == Int(payloadLength), pError == nil else {
                    return
                }

                var fullPacket = header
                fullPacket.append(payload)
                self.processAudioPacket(fullPacket)

                // Continue loop
                self.readTcpPacketHeader(on: connection)
            }
        }
    }

    private func processAudioPacket(_ packet: Data) {
        guard packet.count >= 16 else { return }

        // Validate Magic 'MC'
        guard packet[0] == 0x4D && packet[1] == 0x43 else { return }

        let version = packet[2]
        guard version == 0x02 else { return }

        let flags = packet[3]
        let sequence = UInt16(packet[4]) << 8 | UInt16(packet[5])
        let payloadLen = Int(UInt16(packet[6]) << 8 | UInt16(packet[7]))
        let sessionId = UInt32(packet[12]) << 24 | UInt32(packet[13]) << 16 | UInt32(packet[14]) << 8 | UInt32(packet[15])

        // Verify session ID matches active control session
        let activeSession = ControlServer.shared.currentSessionId
        guard sessionId == activeSession else {
            if !warnedSessionMismatch {
                warnedSessionMismatch = true
                print("[AUDIO RECEIVER] ⚠️ Dropping audio: packet session \(sessionId) != active session \(activeSession)")
            }
            return
        }

        totalPackets += 1
        if totalPackets == 1 {
            print("[AUDIO RECEIVER] First accepted packet: \(packet.count) bytes, payload \(payloadLen), session \(sessionId)")
        } else if totalPackets % 200 == 0 {
            print("[AUDIO RECEIVER] accepted=\(packetsReceived + 1) total=\(totalPackets) lastPayload=\(payloadLen)")
        }

        // Modular sequence rollover check
        if let last = lastSequence {
            let diff = (sequence &- last)
            if diff > 1 && diff < 32768 {
                let lost = UInt64(diff - 1)
                DispatchQueue.main.async {
                    self.packetsLost += lost
                }
            }
        }
        lastSequence = sequence

        DispatchQueue.main.async {
            self.packetsReceived += 1
            self.isReceiving = true
        }

        guard packet.count >= 16 + payloadLen else { return }
        let audioPayload = packet.subdata(in: 16..<(16 + payloadLen))

        // Deliver audio samples to BlackHole virtual driver output
        VirtualAudioOutput.shared.pushAudioSamples(audioPayload)
    }

    public func stop() {
        udpListener?.cancel()
        udpListener = nil
        tcpUsbListener?.cancel()
        tcpUsbListener = nil
        isReceiving = false
    }
}
