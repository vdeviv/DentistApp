# DentApp — Medición de Apertura Bucal con IA 🦷

Aplicación Android para dentistas que mide la apertura bucal máxima y la desviación mandibular usando la cámara del dispositivo y Machine Learning en tiempo real, sin necesidad de internet ni equipos externos.

---

## ¿Qué problema resuelve?

En odontología clínica, la medición de la apertura bucal máxima (MBD — Maximal Mouth Opening) es una exploración rutinaria para diagnosticar disfunciones temporomandibulares (TMJ), trismo, anquilosis, o seguimiento postquirúrgico. Tradicionalmente se realiza con un calibrador o regla colocada físicamente entre los incisivos del paciente.

Esta aplicación reemplaza ese proceso con la cámara del teléfono, permitiendo:

- Medición rápida sin contacto físico
- Registro de la desviación lateral de la mandíbula
- Clasificación automática según rangos clínicos establecidos
- Captura del momento de máxima apertura

**Uso previsto:** herramienta de screening y seguimiento, no de diagnóstico definitivo.

---

## Conceptos clínicos

### Apertura bucal máxima (MBO)

Es la distancia máxima que puede separarse el borde del incisivo superior del borde del incisivo inferior durante la apertura activa. Los rangos clínicos de referencia son:

| Clasificación | Rango | Implicación clínica |
|---|---|---|
| Normal | ≥ 35 mm | Sin restricción de apertura |
| Limitación leve | 25–35 mm | Posible tensión muscular o limitación articular incipiente |
| Limitación moderada | 15–25 mm | Probable disfunción TMJ, trismo o fibrosis |
| Limitación severa | < 15 mm | Anquilosis, contractura grave, intervención urgente |

El promedio en adultos sanos es aproximadamente 50 mm (±6 mm). El umbral de 35 mm está respaldado por la literatura clínica (NCBI/TMJ).

### Desviación mandibular

Al abrir la boca, la mandíbula puede desviarse lateralmente. Esta desviación es relevante clínicamente:

| Desviación | Implicación |
|---|---|
| < 2 mm | Centro — normal |
| 2–5 mm | Leve — posible asimetría muscular |
| > 5 mm | Significativa — asociada a disfunción TMJ o desplazamiento discal |

### Patrones de apertura (trayectoria)

La app registra la trayectoria de la mandíbula durante todo el movimiento de apertura y clasifica el patrón observado:

| Patrón | Descripción clínica |
|---|---|
| Rectilíneo | Normal — apertura sin desviación lateral |
| Desviación en "C" | La mandíbula se desvía hacia un lado y no vuelve — sugerente de reducción de disco |
| Desviación en "S" | La mandíbula se desvía y luego corrige — sugerente de alteración de coordinación muscular |
| Deflexión lateral | La mandíbula se desvía progresivamente sin corrección — sugerente de bloqueo/desplazamiento discal sin reducción |

---

## Arquitectura técnica

```
com.revvivii.mydentapp/
├── MainActivity.kt          — Punto de entrada, manejo de permisos de cámara
├── analyzer/
│   └── FaceMeshAnalyzer.kt  — Análisis de frames con ML Kit, cálculo de métricas
├── model/
│   └── MeasurementResult.kt — Modelo de datos del resultado de medición
└── ui/
    ├── CameraScreen.kt      — UI principal: cámara, overlay, paneles
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

### Stack tecnológico

| Herramienta | Versión | Para qué |
|---|---|---|
| **ML Kit Face Mesh** | 16.0.0-beta3 | Detecta 468 landmarks del rostro — offline, gratuito |
| **CameraX** | 1.4.0 | API moderna de cámara, manejo de lifecycle |
| **Jetpack Compose** | BOM 2024.04 | UI declarativa |
| **Canvas de Compose** | — | Overlay de guías y líneas sobre la cámara |
| **Kotlin Coroutines** | 1.8.1 | Procesamiento asíncrono |

---

## Cómo funciona la medición

### 1. Captura de frames

CameraX entrega frames continuos al `FaceMeshAnalyzer` a través de `ImageAnalysis`. Para optimizar CPU, se analiza 1 de cada 2 frames (≈15 análisis/segundo).

### 2. Detección de landmarks con ML Kit Face Mesh

ML Kit procesa cada frame y devuelve una malla de **468 puntos tridimensionales** del rostro, basada en el modelo MediaPipe Face Mesh de Google. La app extrae 6 puntos específicos:

| Índice | Anatomía | Uso |
|---|---|---|
| `#13` | Centro interior del labio superior | Punto A de la apertura |
| `#14` | Centro interior del labio inferior | Punto B de la apertura |
| `#33` | Comisura exterior del ojo izquierdo | Referencia de escala (IPD) |
| `#263` | Comisura exterior del ojo derecho | Referencia de escala (IPD) |
| `#61` | Comisura izquierda de la boca | Cálculo de desviación y línea horizontal |
| `#291` | Comisura derecha de la boca | Cálculo de desviación y línea horizontal |

### 3. Validación de posición

Antes de medir, la app verifica tres condiciones:

**a) Cara dentro del óvalo:** Se comprueba que los ojos y labios estén centrados y a la distancia correcta usando el IPD en píxeles como proxy de distancia (mínimo 60px, máximo 280px). Esto garantiza que todos los pacientes se midan en condiciones equivalentes.

**b) Inclinación:** La diferencia vertical entre los ojos debe ser menor a 40px. Si la cabeza está inclinada, la distancia vertical entre labios se reduce aunque la apertura real sea la misma.

**c) Centrado horizontal:** El midpoint entre los ojos debe estar dentro del 20% central del ancho de la imagen.

### 4. Conversión píxeles → milímetros (IPD como regla)

La cámara solo ve píxeles. Para convertirlos a milímetros se necesita una referencia de tamaño conocido. La app usa la **distancia interpupilar (IPD)** como regla incorporada:

```
mm_por_px  = IPD_promedio_mm / IPD_medido_px
           = 63.0 / IPD_px

apertura_px = distancia_euclidiana(punto_13, punto_14)
apertura_mm = apertura_px × mm_por_px
```

El IPD promedio en adultos es **63 mm** (rango poblacional: 50–75 mm). Este valor está respaldado por múltiples estudios de óptica y optometría. La variación individual introduce un error estimado de ±5–8 mm, aceptable para screening clínico.

**¿Por qué el óvalo ayuda a reducir el error?** Al forzar que la cara siempre ocupe la misma fracción de pantalla, el IPD en píxeles es similar para todos los pacientes, haciendo la escala mm/px más consistente entre mediciones.

### 5. Cálculo de desviación mandibular

```
faceCenterX  = (ojo_izq.x + ojo_der.x) / 2     ← eje de simetría facial
mouthCenterX = (comisura_izq.x + comisura_der.x) / 2   ← centro de la mandíbula

desviaciónPx = mouthCenterX - faceCenterX
desviaciónMm = desviaciónPx × mm_por_px
```

En cámara frontal, la imagen está espejada por Android. El analizador corrige el signo de la desviación cuando `useFrontCamera = true` para que "Derecha" signifique la derecha del paciente (no de la imagen).

### 6. Detección de pico máximo

La app mantiene una ventana deslizante de las últimas 12 mediciones. Un frame se considera "pico" cuando su valor es el máximo de la ventana y supera al máximo de los frames anteriores. La ventana de 12 frames a ~15 análisis/segundo cubre aproximadamente 0.8 segundos — suficiente para capturar el momento de máxima apertura estable.

### 7. Sistema de confianza

La confianza no proviene de ML Kit (que no reporta puntuación por landmark en Android), sino de un estimador basado en el tamaño del IPD en píxeles:

| IPD en píxeles | Confianza | Razón |
|---|---|---|
| < 120px | 65% | Cara lejos, pocos píxeles por landmark, más ruido |
| 120–180px | 85% | Distancia óptima |
| > 180px | 98% | Cara cerca, landmarks muy estables |

---

## Overlay visual

El `FaceGuideOverlay` dibuja sobre el preview de la cámara en tiempo real:

- **Óvalo guía** — Cambia de amarillo (sin cara) → naranja (fuera del óvalo) → verde (correcto)
- **Puntos verdes** (`#13`, `#14`) — Marcan el labio superior e inferior, puntos de la apertura
- **Línea vertical punteada verde** — Muestra la distancia que se está midiendo
- **Línea horizontal azul** — Une las dos comisuras (`#61` y `#291`), representa el ancho de la boca
- **Línea vertical naranja** — Línea media de la boca. Si coincide con el centro de los labios, no hay desviación. Si está desplazada, indica desviación mandibular
- **Marco amarillo** — Aparece cuando hay una captura congelada

---

## Limitaciones conocidas

**Error de medición por IPD variable:** El mayor factor de error es que el IPD real de la persona puede diferir del promedio de 63 mm. Una persona con IPD de 70 mm tendrá una subestimación de ~10%. Para uso clínico de diagnóstico preciso, se recomienda complementar con medición física.

**Apertura entre labios vs. entre dientes:** La app mide la separación entre los puntos interiores de los labios (`#13` y `#14`), no entre los bordes de los incisivos como en la medición clínica estándar. La diferencia es el grosor labial (típicamente 2–6 mm). La app aplica un offset de +2 mm para aproximar la distancia interincisal cuando la apertura supera los 8 mm.

**Rotación de cabeza (yaw):** La validación de inclinación (roll) funciona bien, pero no se detecta rotación horizontal (yaw). Si el paciente gira ligeramente la cabeza de perfil, la medición pierde precisión.

**Iluminación:** ML Kit requiere iluminación adecuada. En ambientes muy oscuros o con luz trasera fuerte, la detección de landmarks puede fallar.

---

## Permisos requeridos

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

La app solicita el permiso de cámara al primer inicio. Sin él, muestra una pantalla de instrucciones y no accede a la cámara.

---

## Requisitos mínimos

- Android 7.0 (API 24) o superior
- Cámara frontal o trasera
- Sin conexión a internet requerida — todo el procesamiento es local (on-device)
