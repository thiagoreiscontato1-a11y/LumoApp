import CoreGraphics
import Foundation
import Vision

/// Curadoria por rostos com o Vision (equivalente ao `SemanticCurator.java` 0.19.0): reprova só quando um rosto
/// principal saiu do quadro pela lateral ou por baixo (`PhotoCheck.cutFraction`). As antigas heurísticas de olhos
/// fechados, rosto encoberto e rosto virado erravam muito (sombra, óculos, pele escura) e foram retiradas,
/// como no Android.
enum SemanticCurator {
    struct Result {
        var review = false
        var penalty = 0
        var note = ""
        var faces = 0
    }

    static func analyze(_ image: CGImage, sensitivity: Int) -> Result {
        let found = faces(image)
        let cut = PhotoCheck.faceCut(found, width: image.width, height: image.height, sensitivity: sensitivity)
        return Result(review: cut.review, penalty: cut.review ? 2 : 0, note: cut.note, faces: found.count)
    }

    /// Rostos para a correção de exposição (prévia revelada).
    static func faces(_ image: PixelImage) -> [PhotoCheck.Face] {
        guard let cg = try? ImageBridge.cgImage(image) else { return [] }
        return faces(cg)
    }

    /// Rostos em pixels (origem no alto à esquerda): ponto entre os olhos e distância entre as pupilas.
    /// Sem pupilas no resultado, estima pelo retângulo do rosto.
    static func faces(_ image: CGImage) -> [PhotoCheck.Face] {
        let request = VNDetectFaceLandmarksRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        guard (try? handler.perform([request])) != nil else { return [] }
        let w = Double(image.width), h = Double(image.height)
        let size = CGSize(width: image.width, height: image.height)
        return (request.results ?? []).map { face in
            let b = face.boundingBox
            if let l = face.landmarks?.leftPupil?.pointsInImage(imageSize: size).first,
               let r = face.landmarks?.rightPupil?.pointsInImage(imageSize: size).first {
                let dx = Double(r.x - l.x), dy = Double(r.y - l.y)
                let eye = max(2, (dx * dx + dy * dy).squareRoot())
                return PhotoCheck.Face(x: Double(l.x + r.x) / 2, y: h - Double(l.y + r.y) / 2, eye: eye, confidence: Double(face.confidence))
            }
            // Vision: caixa normalizada com origem embaixo à esquerda; os olhos ficam a ~38% do alto da caixa.
            let x = Double(b.midX) * w, y = (1 - Double(b.maxY) + Double(b.height) * 0.38) * h
            return PhotoCheck.Face(x: x, y: y, eye: max(2, Double(b.width) * w * 0.42), confidence: Double(face.confidence))
        }
    }
}
