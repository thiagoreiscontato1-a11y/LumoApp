import CoreGraphics
import Foundation
import Vision

/// Curadoria de pessoas com o Vision (equivalente ao `SemanticCurator.java`):
/// rosto cortado na borda, rosto encoberto, olhos fechados e rosto muito virado.
/// É heurística e não substitui a revisão humana.
enum SemanticCurator {
    struct Result {
        var review = false
        var penalty = 0
        var note = ""
        var faces = 0
    }

    static func analyze(_ image: CGImage, sensitivity rawSensitivity: Int) -> Result {
        let sensitivity = max(0, min(2, rawSensitivity))
        let request = VNDetectFaceLandmarksRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        guard (try? handler.perform([request])) != nil else { return Result() }
        let faces = request.results ?? []
        var reasons: [String] = []
        var penalty = 0
        for face in faces {
            // Caixa normalizada (origem embaixo à esquerda), ampliada para incluir cabeça e ombros.
            let b = face.boundingBox
            let left = b.minX - b.width * 0.35, right = b.maxX + b.width * 0.35
            let bottom = b.minY - b.height * 0.45, top = b.maxY + b.height * 0.25
            if left < 0.025 || right > 0.975 || bottom < 0.015 || top > 0.98 {
                reasons.append("Pessoa/rosto possivelmente cortado")
                penalty += 2
            }
            if face.confidence < 0.45 && sensitivity > 0 {
                reasons.append("Rosto parcialmente encoberto ou difícil de ler")
                penalty += 1
            }
            if sensitivity >= 1, let l = face.landmarks?.leftEye, let r = face.landmarks?.rightEye,
               openness(l) < 0.13 && openness(r) < 0.13 {
                reasons.append("Possível olhos fechados")
                penalty += 1
            }
            if sensitivity >= 2, let yaw = face.yaw?.doubleValue, abs(yaw) > 0.6 {
                reasons.append("Rosto muito virado")
                penalty += 1
            }
        }
        if faces.isEmpty && sensitivity >= 2 { reasons.append("Nenhum rosto detectado") }
        var unique: [String] = []
        for r in reasons where !unique.contains(r) { unique.append(r) }
        let threshold = sensitivity == 0 ? 4 : sensitivity == 1 ? 2 : 1
        return Result(review: penalty >= threshold, penalty: min(4, penalty), note: unique.joined(separator: " · "), faces: faces.count)
    }

    /// Altura/largura do contorno do olho: perto de zero quando o olho está fechado.
    static func openness(_ region: VNFaceLandmarkRegion2D) -> Double {
        let pts = region.normalizedPoints
        guard pts.count >= 4 else { return 1 }
        let xs = pts.map { Double($0.x) }, ys = pts.map { Double($0.y) }
        let w = (xs.max() ?? 0) - (xs.min() ?? 0)
        guard w > 0 else { return 1 }
        return ((ys.max() ?? 0) - (ys.min() ?? 0)) / w
    }
}
