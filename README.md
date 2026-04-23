# DentistApp
Aplicación hecha para el uso de dentistas para medición de apertura bucal.

Stack tecnológico 
HerramientaPara quéML Kit Face MeshDetecta 468 landmarks del rostro incluyendo labiosCameraXAPI moderna de cámara en AndroidJetpack ComposeUI modernaCanvas de AndroidDibujar guías y overlay en pantalla


El flujo sería:

Abrir cámara → detectar rostro en tiempo real
Usar los landmarks de los labios (punto superior e inferior de la boca)
Calcular la distancia en píxeles entre esos puntos
Convertir píxeles → milímetros usando la distancia estimada al rostro (con la distancia entre ojos como referencia de escala)
Clasificar el resultado en rangos clínicos

Rangos clínicos de apertura bucal:

Normal: 35–50 mm
Limitación leve: 25–35 mm
Limitación moderada: 15–25 mm
Limitación severa: < 15 mm

