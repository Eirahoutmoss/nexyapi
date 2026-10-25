package com.eirahoutmoss.nexyapi.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** AR izinde bir nokta: dünya koordinatı (m) ve o noktadaki manyetik sapma düzeyi (0..1). */
class TrailPoint(val x: Float, val y: Float, val z: Float, val level: Float, val detected: Boolean)

/**
 * Deneysel AR çizici: kamera görüntüsünü arka plana çizer ve telefonun geçtiği yerleri
 * (manyetik sapma düzeyine göre renkli) noktalar olarak sahneye bırakır.
 *
 * GL iş parçacığında çalışır. ARCore istisnaları yakalanır; çizici asla uygulamayı çökertmez,
 * durum [onStatus] ile bildirilir.
 */
class ArRenderer(
    private val session: Session,
    private val displayRotation: () -> Int,
    /** (düzey 0..1, eşik aşıldı mı) — kalibrasyon yoksa null. */
    private val reading: () -> Pair<Float, Boolean>?,
    private val onStatus: (String?) -> Unit,
) : GLSurfaceView.Renderer {

    private val trail = ArrayList<TrailPoint>()
    private val lock = Any()
    @Volatile var recording = true

    val pointCount: Int get() = synchronized(lock) { trail.size }
    fun clear() = synchronized(lock) { trail.clear() }
    fun snapshot(): List<TrailPoint> = synchronized(lock) { trail.toList() }

    private var oesTexture = 0
    private var bgProgram = 0
    private var ptProgram = 0
    private var viewW = 1
    private var viewH = 1
    private var lastStatus: String? = "init"

    private val quadNdc: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(FloatArray(8))
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session.setCameraTextureName(oesTexture)
        try {
            bgProgram = program(BG_VS, BG_FS)
            ptProgram = program(PT_VS, PT_FS)
        } catch (e: IllegalStateException) {
            // GL iş parçacığında fırlayan istisna uygulamayı kapatır; bunun yerine durum bildir.
            bgProgram = 0; ptProgram = 0
            status("Bu cihazın grafik birimi AR görünümünü desteklemiyor.")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES20.glViewport(0, 0, width, height)
        session.setDisplayGeometry(displayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (bgProgram == 0 || ptProgram == 0) return
        val frame = try {
            session.update()
        } catch (e: Exception) {
            status("AR kamerası kullanılamıyor: ${e.javaClass.simpleName}")
            return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quadNdc.position(0); quadTex.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc, Coordinates2d.TEXTURE_NORMALIZED, quadTex)
        }
        if (frame.timestamp == 0L) return
        drawBackground()

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            status(
                when (camera.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Ortam çok karanlık."
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Duvarda yeterli desen yok; düz duvarlarda AR tutunamayabilir."
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Telefonu daha yavaş hareket ettirin."
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Kamera kullanılamıyor."
                    else -> "AR konumu arıyor… Telefonu yavaşça gezdirin."
                },
            )
            return
        }
        status(null)

        if (recording) {
            val r = reading()
            if (r != null) {
                val p = camera.pose
                synchronized(lock) {
                    val last = trail.lastOrNull()
                    val dx = (last?.x ?: 1e3f) - p.tx(); val dy = (last?.y ?: 1e3f) - p.ty(); val dz = (last?.z ?: 1e3f) - p.tz()
                    if (dx * dx + dy * dy + dz * dz > 0.01f * 0.01f && trail.size < MAX_POINTS) {
                        trail += TrailPoint(p.tx(), p.ty(), p.tz(), r.first, r.second)
                    }
                }
            }
        }

        camera.getProjectionMatrix(proj, 0, 0.03f, 30f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        drawPoints()
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram, "u_Tex"), 0)
        val aPos = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        val aTex = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        quadNdc.position(0); quadTex.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadNdc)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
    }

    private fun drawPoints() {
        val pts = snapshot()
        if (pts.isEmpty()) return
        val data = FloatArray(pts.size * 7)
        pts.forEachIndexed { i, p ->
            val o = i * 7
            data[o] = p.x; data[o + 1] = p.y; data[o + 2] = p.z
            // yeşil (temiz) → sarı → kırmızı (güçlü sapma); temiz noktalar soluk
            val l = if (p.detected) p.level.coerceIn(0f, 1f) else 0f
            data[o + 3] = if (l < 0.5f) 0.30f + 1.4f * l else 1f
            data[o + 4] = if (l < 0.5f) 0.69f else 0.69f * (1 - (l - 0.5f) * 2) + 0.2f
            data[o + 5] = if (p.detected) 0.2f else 0.49f
            data[o + 6] = if (p.detected) 0.95f else 0.35f
        }
        val buf = floatBuffer(data)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ptProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProgram, "u_Mvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProgram, "u_Size"), viewW / 40f)
        val aPos = GLES20.glGetAttribLocation(ptProgram, "a_Position")
        val aCol = GLES20.glGetAttribLocation(ptProgram, "a_Color")
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 7 * 4, buf)
        buf.position(3)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 7 * 4, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pts.size)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun status(s: String?) {
        if (s != lastStatus) { lastStatus = s; onStatus(s) }
    }

    private companion object {
        const val MAX_POINTS = 6000

        const val BG_VS = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        const val BG_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Tex;
            varying vec2 v_TexCoord;
            void main() { gl_FragColor = texture2D(u_Tex, v_TexCoord); }
        """
        const val PT_VS = """
            uniform mat4 u_Mvp;
            uniform float u_Size;
            attribute vec4 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position.xyz, 1.0);
                // Uzaktaki noktalar küçülsün
                gl_PointSize = clamp(u_Size / max(gl_Position.w, 0.1), 4.0, 64.0);
                v_Color = a_Color;
            }
        """
        const val PT_FS = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                if (dot(d, d) > 0.25) discard;
                gl_FragColor = v_Color;
            }
        """

        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src.trimIndent())
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw IllegalStateException("Gölgelendirici derlenemedi: $log")
            }
            return s
        }

        fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Gölgelendirici bağlanamadı: ${GLES20.glGetProgramInfoLog(p)}")
            return p
        }
    }
}
