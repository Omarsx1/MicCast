import SwiftUI
import AppKit

final class MicCastUIState: ObservableObject {
    @Published var showOptions = false
}

struct MicCastMenuBarView: View {
    @EnvironmentObject var controlServer: ControlServer
    @EnvironmentObject var audioReceiver: AudioReceiver
    @EnvironmentObject var audioOutput: VirtualAudioOutput
    @Environment(\.colorScheme) private var colorScheme

    @StateObject private var uiState = MicCastUIState()

    // MARK: - Palette

    private var isDark: Bool { colorScheme == .dark }

    private var panelFill: Color { isDark ? Color(white: 0.16) : Color(white: 0.975) }
    private var cardFill: Color { isDark ? Color(white: 0.215) : Color.white }
    private var chipFill: Color { isDark ? Color(white: 0.30) : Color(white: 0.955) }
    private var borderColor: Color { isDark ? Color.white.opacity(0.09) : Color.black.opacity(0.075) }
    private var trackFill: Color { isDark ? Color.white.opacity(0.13) : Color.black.opacity(0.08) }

    // MARK: - State

    private var isConnected: Bool { controlServer.isClientConnected }

    /// Both symbols are verified present on the deployment target's SF Symbols set.
    private var statusSymbol: String { isConnected ? "waveform" : "mic.slash.fill" }
    private var statusTint: Color { isConnected ? .green : .orange }

    private var statusText: String {
        isConnected ? "Conectado a \(controlServer.connectedClientHost)" : "Esperando conexión"
    }

    private var virtualOutput: VirtualAudioOutput.AudioDevice? {
        audioOutput.availableDevices.first { $0.isBlackHole }
    }

    /// True when the driver bundle exists on disk, even if CoreAudio is not exposing it.
    private var blackHoleDriverOnDisk: Bool {
        let dir = "/Library/Audio/Plug-Ins/HAL"
        let items = (try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []
        return items.contains { $0.hasPrefix("BlackHole") && $0.hasSuffix(".driver") }
    }

    private enum VirtualOutputStatus {
        case ready(name: String)
        case installedNotLoaded
        case missing
    }

    private var virtualOutputStatus: VirtualOutputStatus {
        if let device = virtualOutput { return .ready(name: device.name) }
        return blackHoleDriverOnDisk ? .installedNotLoaded : .missing
    }

    private var outputIcon: String {
        switch virtualOutputStatus {
        case .ready: return "waveform"
        case .installedNotLoaded, .missing: return "exclamationmark.triangle.fill"
        }
    }

    private var outputTint: Color {
        switch virtualOutputStatus {
        case .ready: return .green
        case .installedNotLoaded, .missing: return .orange
        }
    }

    private var outputTitle: String {
        switch virtualOutputStatus {
        case let .ready(name): return name
        case .installedNotLoaded: return "BlackHole instalado, no disponible"
        case .missing: return "BlackHole no instalado"
        }
    }

    private var outputDetail: String {
        switch virtualOutputStatus {
        case .ready: return "Micrófono virtual para Zoom, OBS y demás apps"
        case .installedNotLoaded: return "sudo killall coreaudiod"
        case .missing: return "brew install blackhole-2ch"
        }
    }

    private var outputDetailIsCommand: Bool {
        if case .ready = virtualOutputStatus { return false }
        return true
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            pairingSection
            inputLevelSection
            outputSection
            optionsSection
            footer
        }
        .padding(12)
        .frame(width: 320)
        .background(panelFill)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 7) {
            Circle()
                .fill(statusTint.opacity(isDark ? 0.20 : 0.13))
                .frame(width: 46, height: 46)
                .overlay(
                    Image(systemName: statusSymbol)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(statusTint)
                )
                .overlay(
                    Circle()
                        .stroke(statusTint.opacity(0.30), lineWidth: 1)
                )

            HStack(spacing: 5) {
                Circle()
                    .fill(statusTint)
                    .frame(width: 6, height: 6)

                Text(statusText)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
        .padding(.bottom, 2)
    }

    // MARK: - Sections

    private var pairingSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("EMPAREJAMIENTO") {
                Button("Nuevo PIN") {
                    controlServer.generateNewPin()
                }
                .buttonStyle(.plain)
                .font(.system(size: 10.5, weight: .medium))
                .foregroundStyle(Color.accentColor)
            }

            card {
                VStack(spacing: 8) {
                    HStack(spacing: 5) {
                        Spacer(minLength: 0)

                        ForEach(Array(controlServer.currentPin.enumerated()), id: \.offset) { _, digit in
                            Text(String(digit))
                                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                .frame(width: 30, height: 30)
                                .background(
                                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                                        .fill(chipFill)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                                        .stroke(borderColor, lineWidth: 1)
                                )
                        }

                        Spacer(minLength: 0)
                    }

                    Text("Ingresa este código en la app MicCast de tu Android")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var inputLevelSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("NIVEL DE ENTRADA") {
                Text(String(format: "%.0f dBFS", audioOutput.currentDbfs))
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(.secondary)
            }

            card(padding: 11) {
                GeometryReader { geo in
                    let norm = CGFloat((audioOutput.currentDbfs + 60.0) / 60.0).clamped(to: 0.0...1.0)

                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(trackFill)

                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [.green, .yellow, .red],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: geo.size.width * norm)
                    }
                }
                .frame(height: 5)
            }
        }
    }

    private var outputSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("SALIDA VIRTUAL")

            card {
                HStack(spacing: 8) {
                    Image(systemName: outputIcon)
                        .font(.system(size: 11))
                        .frame(width: 16)
                        .foregroundStyle(outputTint)

                    VStack(alignment: .leading, spacing: 1) {
                        Text(outputTitle)
                            .font(.system(size: 11.5, weight: .medium))
                            .lineLimit(1)
                            .truncationMode(.middle)

                        Text(outputDetail)
                            .font(.system(size: 9.5, design: outputDetailIsCommand ? .monospaced : .default))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 4)

                    if case .ready = virtualOutputStatus {
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.green)
                    }
                }
            }
        }
    }

    // MARK: - Options

    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) {
                    uiState.showOptions.toggle()
                }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 8, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(uiState.showOptions ? 90 : 0))

                    Text("Opciones")
                        .font(.system(size: 11.5, weight: .medium))

                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)

            if uiState.showOptions {
                card {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("DISPOSITIVOS EMPAREJADOS")
                            .font(.system(size: 9.5, weight: .semibold))
                            .tracking(0.8)
                            .foregroundStyle(.secondary)

                        if controlServer.pairedDevices.isEmpty {
                            Text("Ninguno — el primer PIN crea un token para tu teléfono.")
                                .font(.system(size: 10))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        } else {
                            ForEach(controlServer.pairedDevices) { device in
                                HStack(spacing: 7) {
                                    Image(systemName: "iphone")
                                        .font(.system(size: 10))
                                        .frame(width: 13)
                                        .foregroundStyle(.secondary)

                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(device.name)
                                            .font(.system(size: 11, weight: .medium))
                                            .lineLimit(1)

                                        Text(device.pairedAt, style: .date)
                                            .font(.system(size: 9))
                                            .foregroundStyle(.secondary)
                                    }

                                    Spacer(minLength: 6)

                                    Button {
                                        controlServer.revokeDevice(device.id)
                                    } label: {
                                        Image(systemName: "trash")
                                            .font(.system(size: 10))
                                            .foregroundStyle(.red)
                                            .padding(4)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .help("Revocar este dispositivo")
                                }
                            }
                        }

                        Rectangle().fill(borderColor).frame(height: 1)

                        metricRow("Paquetes", "\(audioReceiver.packetsReceived)")
                        metricRow(
                            "Pérdidas",
                            "\(audioReceiver.packetsLost)",
                            highlight: audioReceiver.packetsLost > 0
                        )
                        metricRow(
                            "Cliente",
                            isConnected ? controlServer.connectedClientHost : "—"
                        )
                    }
                }
            }
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(spacing: 7) {
            footerButton("Instrucciones", systemImage: "terminal") {
                showUsbHelpAlert()
            }
            footerButton("Salir", systemImage: "power") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(.top, 1)
    }

    // MARK: - Building blocks

    private func sectionHeader<T: View>(
        _ title: String,
        @ViewBuilder trailing: () -> T
    ) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 9.5, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(.secondary)

            Spacer(minLength: 8)

            trailing()
        }
        .padding(.horizontal, 2)
    }

    private func sectionHeader(_ title: String) -> some View {
        sectionHeader(title) { EmptyView() }
    }

    private func card<Content: View>(
        padding: CGFloat = 10,
        @ViewBuilder content: () -> Content
    ) -> some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(cardFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(borderColor, lineWidth: 1)
            )
    }

    private func metricRow(
        _ title: String,
        _ value: String,
        highlight: Bool = false
    ) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)

            Spacer(minLength: 8)

            Text(value)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(highlight ? Color.red : Color.primary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private func footerButton(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.system(size: 9.5))

                Text(title)
                    .font(.system(size: 11, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(cardFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(borderColor, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Alert

    private func showUsbHelpAlert() {
        let alert = NSAlert()
        alert.messageText = "Conexión por Cable USB"
        alert.informativeText = "Para conectar con latencia sub-15ms por USB:\n\n1. Conecta el teléfono por cable con depuración USB habilitada.\n2. Ejecuta en Terminal:\nadb reverse tcp:51516 tcp:51516 && adb reverse tcp:51517 tcp:51517\n3. En la app Android toca 'Conectar vía USB'."
        alert.addButton(withTitle: "Entendido")
        alert.runModal()
    }
}

extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        return min(max(self, limits.lowerBound), limits.upperBound)
    }
}
