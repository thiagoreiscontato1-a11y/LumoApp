import SwiftUI

/// Tour inicial (equivalente ao tour do Android), também disponível em Ajustes.
struct TourView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0

    struct Step: Identifiable {
        let id: Int
        let icon: String
        let title: String
        let text: String
    }

    static let steps: [Step] = [
        Step(id: 0, icon: "calendar.badge.plus", title: "Um evento por trabalho",
             text: "Crie o evento no topo. Originais, editadas e fotos em revisão ficam separadas em pastas do evento, visíveis no app Arquivos."),
        Step(id: 1, icon: "cable.connector", title: "Conecte a câmera",
             text: "Cabo USB com Wi-Fi e Bluetooth da câmera desligados, ou Wi-Fi da câmera. Fotografe normalmente: cada foto chega em segundos."),
        Step(id: 2, icon: "wand.and.stars", title: "Edição e curadoria automáticas",
             text: "O Lumo aplica o automático e o seu preset, dá uma nota de 1 a 10 e separa fotos tremidas, escuras, com olhos fechados ou cortadas na Revisão."),
        Step(id: 3, icon: "hand.draw", title: "Galeria na ponta do dedo",
             text: "Deslize entre as fotos, dê zoom, favorite, edite vendo o resultado na hora e copie os ajustes para outras fotos."),
        Step(id: 4, icon: "icloud.and.arrow.up", title: "Entrega no Fotto",
             text: "Conecte o Fotto, escolha o evento de destino e ative o envio automático. Fotos em Revisão só vão depois de aprovadas."),
        Step(id: 5, icon: "bolt.shield", title: "Modo evento",
             text: "Em Ajustes, o modo evento mantém a tela ligada e toca um alarme se a câmera desconectar — mesmo no silencioso."),
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Pular") { dismiss() }.foregroundStyle(Lumo.secondary).padding()
            }
            TabView(selection: $page) {
                ForEach(Self.steps) { step in
                    VStack(spacing: 22) {
                        Spacer()
                        ZStack {
                            Circle().fill(Lumo.gradient.opacity(0.18)).frame(width: 150, height: 150)
                            Image(systemName: step.icon).font(.system(size: 58, weight: .semibold)).foregroundStyle(Lumo.gradient)
                        }
                        Text(step.title).font(.system(.title, design: .rounded).weight(.bold)).multilineTextAlignment(.center)
                        Text(step.text).font(.body).foregroundStyle(Lumo.secondary).multilineTextAlignment(.center).padding(.horizontal, 30)
                        Spacer()
                    }
                    .tag(step.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            Button(page == Self.steps.count - 1 ? "Começar" : "Próximo") {
                if page == Self.steps.count - 1 { dismiss() } else { withAnimation { page += 1 } }
            }
            .buttonStyle(LumoPrimaryButtonStyle())
            .padding(20)
        }
        .foregroundStyle(.white)
        .background(Lumo.background.ignoresSafeArea())
    }
}
