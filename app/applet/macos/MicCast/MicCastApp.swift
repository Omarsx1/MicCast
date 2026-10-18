import SwiftUI
import AppKit

@main
struct MicCastApp: App {
    @StateObject private var controlServer = ControlServer.shared
    @StateObject private var audioReceiver = AudioReceiver.shared
    @StateObject private var audioOutput = VirtualAudioOutput.shared

    init() {
        controlServer.start()
        audioReceiver.start()
        audioOutput.start()
    }

    var body: some Scene {
        MenuBarExtra("MicCast", systemImage: controlServer.isClientConnected ? "mic.fill" : "mic.slash") {
            MicCastMenuBarView()
                .environmentObject(controlServer)
                .environmentObject(audioReceiver)
                .environmentObject(audioOutput)
        }
        .menuBarExtraStyle(.window)
    }
}

final class MicCastUIState: ObservableObject {
    @Published var showOptions = false
}

struct MicCastMenuBarView: View {
    @EnvironmentObject var controlServer: ControlServer
    @EnvironmentObject var audioReceiver: AudioReceiver
    @EnvironmentObject var audioOutput: VirtualAudioOutput

    @StateObject private var uiState = MicCastUIState()

    // MARK: - Surfaces tuned to the system material

    private var cardFill: Color { Color.primary.opacity(0.045) }
    private var chipFill: Color { Color.primary.opacity(0.07) }
    private var hairline: Color { Color.primary.opacity(0.09) }
    private var trackFill: Color { Color.primary.opacity(0.10) }

    private var statusText: String {
        controlServer.isClientConnected
            ? "Conectado a \(controlServer.connectedClientHost)"
            : "Esperando conexión"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header

            VStack(alignment: .leading, spacing: 7) {
                sectionHeader("EMPAREJAMIENTO") {
                    Button("Nuevo PIN") {
                        controlServer.generateNewPin()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(Color.accentColor)
                }
                pinCard
            }

            VStack(alignment: .leading, spacing: 7) {
                sectionHeader("NIVEL DE ENTRADA") {
                    Text(String(format: "%.0f dBFS", audioOutput.currentDbfs))
                        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
                levelCard
            }

            VStack(alignment: .leading, spacing: 7) {
                sectionHeader("SALIDA DE AUDIO")
                outputCard
            }

            optionsSection

            footer
        }
        .padding(14)
        .frame(width: 320)
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "mic.badge.waveform")
                .font(.system(size: 34, weight: .regular))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.primary)

            HStack(spacing: 5) {
                Circle()
                    .fill(controlServer.isClientConnected ? Color.green : Color.orange)
                    .frame(width: 6, height: 6)

                Text(statusText)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
    }

    // MARK: - Cards

    private var pinCard: some View {
        card {
            VStack(spacing: 9) {
                HStack(spacing: 6) {
                    ForEach(Array(controlServer.currentPin.enumerated()), id: \.offset) { _, digit in
                        Text(String(digit))
                            .font(.system(size: 17, weight: .semibold, design: .monospaced))
                            .frame(maxWidth: .infinity)
                            .frame(height: 34)
                            .background(
                                RoundedRectangle(cornerRadius: 7)
                                    .fill(chipFill)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 7)
                                    .stroke(hairline, lineWidth: 1)
                            )
                    }
                }

                Text("Ingresa este código en la app MicCast de tu Android")
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var levelCard: some View {
        card {
            GeometryReader { geo in
                let norm = CGFloat((audioOutput.currentDbfs + 60.0) / 60.0).clamped(to: 0.0...1.0)

                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(trackFill)
                        .frame(height: 6)

                    Capsule()
                        .fill(
                            LinearGradient(
                                colors: [.green, .yellow, .red],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(width: geo.size.width * norm, height: 6)
                }
                .frame(height: 6)
            }
            .frame(height: 6)
        }
    }

    private var outputCard: some View {
        card {
            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    Text("Dispositivo")
                        .font(.system(size: 11.5))

                    Spacer(minLength: 8)

                    Picker("", selection: $audioOutput.selectedDevice) {
                        ForEach(audioOutput.availableDevices) { device in
                            HStack {
                                Text(device.name)
                                if device.isBlackHole {
                                    Text("(Recomendado)").foregroundColor(.green)
                                }
                            }
                            .tag(Optional(device))
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .controlSize(.small)
                    .frame(maxWidth: 185)
                }

                if !(audioOutput.selectedDevice?.isBlackHole ?? false) {
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 9))
                        Text("Instala BlackHole 2ch para usar en Zoom/OBS")
                            .font(.system(size: 10))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(.orange)
                }
            }
        }
    }

    // MARK: - Options

    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 7) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) {
                    uiState.showOptions.toggle()
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 8, weight: .semibold))
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
                    VStack(alignment: .leading, spacing: 8) {
                        metricRow("Paquetes", "\(audioReceiver.packetsReceived)")
                        Divider().overlay(hairline)
                        metricRow(
                            "Pérdidas",
                            "\(audioReceiver.packetsLost)",
                            highlight: audioReceiver.packetsLost > 0
                        )
                        Divider().overlay(hairline)
                        metricRow(
                            "Cliente",
                            controlServer.isClientConnected ? controlServer.connectedClientHost : "—"
                        )
                    }
                }
            }
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(spacing: 8) {
            footerButton("Instrucciones", systemImage: "terminal") {
                showUsbHelpAlert()
            }
            footerButton("Salir", systemImage: "power") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(.top, 2)
    }

    // MARK: - Building blocks

    private func sectionHeader<T: View>(
        _ title: String,
        @ViewBuilder trailing: () -> T
    ) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .tracking(0.7)
                .foregroundStyle(.secondary)

            Spacer(minLength: 8)

            trailing()
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        sectionHeader(title) { EmptyView() }
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(cardFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(hairline, lineWidth: 1)
            )
    }

    private func metricRow(
        _ title: String,
        _ value: String,
        highlight: Bool = false
    ) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)

            Spacer(minLength: 8)

            Text(value)
                .font(.system(size: 11.5, weight: .medium, design: .monospaced))
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
                    .font(.system(size: 10.5))

                Text(title)
                    .font(.system(size: 11.5, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 7)
                    .fill(cardFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7)
                    .stroke(hairline, lineWidth: 1)
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
