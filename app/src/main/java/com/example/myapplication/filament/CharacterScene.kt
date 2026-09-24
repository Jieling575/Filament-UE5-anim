package com.example.myapplication.filament

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.myapplication.combo.ComboStateMachine
import com.example.myapplication.combo.Move
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.tan

/**
 * 基础 Filament 场景：Engine / Renderer / Scene / View / Camera / Light，
 * 加载 glb 角色模型，并用 Choreographer 驱动逐帧渲染和骨骼动画。
 * 播放哪段动画、播到第几秒由 [ComboStateMachine] 决定，这里只负责把结果应用到骨骼上。
 *
 * [transparentBackground] 为 true 时不画背景、输出带 alpha 的画面（AR 模式叠加在摄像头预览上用），
 * 否则用纯色 skybox 做背景。
 *
 * [hiddenUntilPlaced] 为 true 时角色一开始不在场景里，调用 [place] 后才以弹出动画出现在点击位置
 * （AR 模式点击屏幕放置用）；否则一创建就显示在画面中央。
 *
 * 所有方法都在主线程调用。
 */
class CharacterScene(
    context: Context,
    modelAssetPath: String,
    private val transparentBackground: Boolean = false,
    hiddenUntilPlaced: Boolean = false,
) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "CharacterScene"

        /** 单帧时间步长上限，避免卡顿或从后台恢复时动画一下子跳过一大截。 */
        private const val MAX_FRAME_DELTA_SECONDS = 0.1f

        /** 切换动作时新旧两段动画的混合时长。 */
        private const val CROSS_FADE_SECONDS = 0.15f

        /**
         * Filament 的 applyAnimation 会对时间取模，传入恰好等于时长的时间会跳回第一帧，
         * 一次性动画要取末帧时用 时长 - 这个值。
         */
        private const val CLIP_END_EPSILON = 1e-4f

        /** 相机位于 (0, 0, CAMERA_DISTANCE) 看向原点。 */
        private const val CAMERA_DISTANCE = 5.5

        /** 相机垂直视角（度）。 */
        private const val CAMERA_FOV_DEGREES = 45.0

        // 可放置区域，用 NDC 坐标表示（-1 为屏幕下沿/左沿，1 为上沿/右沿），点击超出范围时就近夹到边界。
        // 纵向 [-0.6, 0.2] 约为屏幕从上往下 40% ~ 80% 处：再往上角色会顶到状态栏，再往下会压住攻击按钮。
        private const val PLACE_NEAR_NDC_Y = -0.6f
        private const val PLACE_FAR_NDC_Y = 0.2f
        private const val PLACE_MAX_NDC_X = 0.8f

        /** 近大远小：脚底在可放置区域最下沿（近）和最上沿（远）时的缩放，中间线性插值。 */
        private const val PLACE_NEAR_SCALE = 0.8f
        private const val PLACE_FAR_SCALE = 0.35f

        /** 弹出动画时长。 */
        private const val POP_IN_SECONDS = 0.3f

        /** 弹出动画的起始缩放，不从 0 开始，避免缩放为 0 的退化矩阵。 */
        private const val POP_IN_START_SCALE = 0.01f

        init {
            Filament.init()
            Gltfio.init()
        }
    }

    private val engine: Engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity)
    private val sunEntity = EntityManager.get().create()
    private val indirectLight: IndirectLight
    private val skybox: Skybox?

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val asset: FilamentAsset
    private val animator: Animator

    /** 把模型归一化到原点附近、边长为 2 的立方体内的变换，见 [computeFitTransform]。 */
    private val fitTransform: FloatArray

    /** 归一化后脚底（绑定姿势包围盒底部）的 y 坐标。 */
    private var fitFootY = 0f

    /** 角色是否已加入场景（可见）。首次放置后一直为 true，不限制之后重新放置。 */
    var isPlaced = !hiddenUntilPlaced
        private set

    /** 弹出动画已经播放的时间，小于 0 表示没有在播放。 */
    private var popInElapsed = -1f

    // 放置后脚底的世界坐标（z = 0 平面上）和近大远小的缩放
    private var placedFootX = 0f
    private var placedFootY = 0f
    private var placedScale = 1f

    // 渲染区域大小（像素），用于把点击坐标换算到世界坐标
    private var viewportWidth = 0
    private var viewportHeight = 0

    /** 每个动作在 glb 中的动画索引，按名字查出来的。 */
    private val animationIndex: Map<Move, Int>

    private val comboStateMachine: ComboStateMachine

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var displayHelper: DisplayHelper? = null
    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L

    // crossfade：切换动作后的一小段时间里，把上一段动画的姿势混合进来，避免画面跳变
    private var fadeFromIndex = -1
    private var fadeFromTime = 0f
    private var fadeElapsed = 0f

    init {
        view.scene = scene
        view.camera = camera

        if (transparentBackground) {
            // 没有 skybox，每帧清成 alpha = 0，没画到角色的像素透出下层的摄像头画面
            skybox = null
            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
            // 默认的 OPAQUE 会让后处理把 alpha 当成 1 输出
            view.blendMode = View.BlendMode.TRANSLUCENT
        } else {
            skybox = Skybox.Builder().color(0.12f, 0.12f, 0.14f, 1.0f).build(engine)
            scene.skybox = skybox
        }

        // 主光源：斜上方打下来的方向光
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(100_000.0f)
            .direction(-0.5f, -1.0f, -0.6f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // 没有环境贴图，用一阶球谐的常量环境光把背光面提亮，避免死黑
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.0f))
            .intensity(30_000.0f)
            .build(engine)
        scene.indirectLight = indirectLight

        asset = loadAsset(context, modelAssetPath)
        fitTransform = computeFitTransform(asset)
        if (isPlaced) {
            scene.addEntities(asset.entities)
            setRootTransform(fitTransform)
        }

        animator = asset.instance.animator
        animationIndex = findAnimationIndices(animator)

        val durations = Move.entries.associateWith { move ->
            animator.getAnimationDuration(animationIndex.getValue(move))
        }
        Log.d(TAG, "动画时长: $durations")
        comboStateMachine = ComboStateMachine(
            durations = durations,
            cancelPoint = ComboStateMachine.DEFAULT_CANCEL_POINT,
            onMoveChanged = ::onMoveChanged,
        )

        camera.lookAt(
            0.0, 0.0, CAMERA_DISTANCE,
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
        )
    }

    private fun loadAsset(context: Context, path: String): FilamentAsset {
        val bytes = context.assets.open(path).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
            .flip()
        val asset = assetLoader.createAsset(buffer)
            ?: error("无法解析模型文件: $path")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        return asset
    }

    /**
     * 用 getAnimationName(i) 遍历 glb 里的全部动画，按名字建立 动作 -> 索引 的映射。
     * 缺少任何一段都直接报错，免得运行时静默地播错动画。
     */
    private fun findAnimationIndices(animator: Animator): Map<Move, Int> {
        val indexByName = (0 until animator.animationCount)
            .associateBy { animator.getAnimationName(it) }
        Log.d(TAG, "glb 中的动画: $indexByName")
        return Move.entries.associateWith { move ->
            indexByName[move.animationName]
                ?: error("character.glb 中缺少动画 \"${move.animationName}\"，现有: ${indexByName.keys}")
        }
    }

    /**
     * 计算把模型缩放、平移到以原点为中心、边长为 2 的立方体内的变换，方便摆相机。
     *
     * 不能用 asset.boundingBox：它会叠加网格节点的父级变换（Mixamo 导出的 Armature 带 0.01 缩放和 90° 旋转），
     * 而蒙皮网格实际由骨骼矩阵摆放、不受这些节点变换影响，算出来的盒子会比角色小 100 倍。
     * 这里改用各 renderable 的本地包围盒，对蒙皮网格来说就是绑定姿势下的顶点范围。
     */
    private fun computeFitTransform(asset: FilamentAsset): FloatArray {
        val rm = engine.renderableManager
        val min = FloatArray(3) { Float.MAX_VALUE }
        val maxCorner = FloatArray(3) { -Float.MAX_VALUE }
        for (entity in asset.renderableEntities) {
            val box = rm.getAxisAlignedBoundingBox(rm.getInstance(entity), null)
            for (i in 0..2) {
                min[i] = minOf(min[i], box.center[i] - box.halfExtent[i])
                maxCorner[i] = max(maxCorner[i], box.center[i] + box.halfExtent[i])
            }
        }
        val center = FloatArray(3) { (min[it] + maxCorner[it]) / 2f }
        val halfExtent = FloatArray(3) { (maxCorner[it] - min[it]) / 2f }
        Log.d(TAG, "asset.boundingBox 半尺寸: ${asset.boundingBox.halfExtent.contentToString()}，蒙皮网格半尺寸: ${halfExtent.contentToString()}")
        val maxExtent = 2.0f * max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
        val scale = 2.0f / maxExtent
        fitFootY = (min[1] - center[1]) * scale
        // 列主序矩阵：先平移到原点，再统一缩放
        return floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            -center[0] * scale, -center[1] * scale, -center[2] * scale, 1f,
        )
    }

    private fun setRootTransform(transform: FloatArray) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), transform)
    }

    /**
     * 放置后的变换：在归一化的基础上以脚底为支点缩放，再把脚底移到放置点。
     * [popScale] 是弹出动画的进度缩放，1 为最终大小。
     */
    private fun applyPlacedTransform(popScale: Float) {
        val placement = FloatArray(16)
        Matrix.setIdentityM(placement, 0)
        Matrix.translateM(placement, 0, placedFootX, placedFootY, 0f)
        val scale = placedScale * popScale
        Matrix.scaleM(placement, 0, scale, scale, scale)
        Matrix.translateM(placement, 0, 0f, -fitFootY, 0f)
        val transform = FloatArray(16)
        Matrix.multiplyMM(transform, 0, placement, 0, fitTransform, 0)
        setRootTransform(transform)
    }

    /**
     * 在点击位置放置角色：脚底落在点击点（渲染区域内的像素坐标）反投影到 z = 0 平面的位置，
     * 越靠下缩放越大、越靠上越小，模拟近大远小。
     *
     * 可以反复调用：每次都移动到新位置、重播弹出动画，并把连招重置回 idle，
     * 丢弃上一次打到一半的连招和 crossfade。
     *
     * @return 是否放置成功；渲染区域大小还未知（Surface 未就绪）时返回 false，不做任何事
     */
    fun place(screenX: Float, screenY: Float): Boolean {
        if (viewportWidth == 0 || viewportHeight == 0) return false

        val ndcX = (2f * screenX / viewportWidth - 1f).coerceIn(-PLACE_MAX_NDC_X, PLACE_MAX_NDC_X)
        val ndcY = (1f - 2f * screenY / viewportHeight).coerceIn(PLACE_NEAR_NDC_Y, PLACE_FAR_NDC_Y)
        // z = 0 平面上可见范围的一半高度 / 宽度
        val halfHeight = (CAMERA_DISTANCE * tan(Math.toRadians(CAMERA_FOV_DEGREES / 2))).toFloat()
        val halfWidth = halfHeight * viewportWidth / viewportHeight
        placedFootX = ndcX * halfWidth
        placedFootY = ndcY * halfHeight
        val farness = (ndcY - PLACE_NEAR_NDC_Y) / (PLACE_FAR_NDC_Y - PLACE_NEAR_NDC_Y)
        placedScale = PLACE_NEAR_SCALE + (PLACE_FAR_SCALE - PLACE_NEAR_SCALE) * farness

        comboStateMachine.reset()
        fadeFromIndex = -1

        applyPlacedTransform(POP_IN_START_SCALE)
        if (!isPlaced) {
            isPlaced = true
            scene.addEntities(asset.entities)
        }
        popInElapsed = 0f
        return true
    }

    private fun updatePopIn(deltaSeconds: Float) {
        if (popInElapsed < 0f) return
        popInElapsed += deltaSeconds
        val t = (popInElapsed / POP_IN_SECONDS).coerceAtMost(1f)
        // ease-out cubic：开头快、结尾慢慢停住
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        applyPlacedTransform(POP_IN_START_SCALE + (1f - POP_IN_START_SCALE) * eased)
        if (t >= 1f) popInElapsed = -1f
    }

    fun attachTo(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        displayHelper = DisplayHelper(surfaceView.context)
        uiHelper.renderCallback = SurfaceCallback()
        if (transparentBackground) {
            // UiHelper 据此把 Surface 设为 TRANSLUCENT，并用 setZOrderMediaOverlay 叠在
            // 摄像头预览的 SurfaceView 之上、应用窗口之下（setZOrderOnTop 会盖住 Compose 的按钮）
            uiHelper.isOpaque = false
            uiHelper.isMediaOverlay = true
        }
        uiHelper.attachTo(surfaceView)
    }

    /** "攻击"按钮的点击入口，具体是立即出招还是记为预输入由状态机判断。 */
    fun attack() {
        comboStateMachine.onAttack()
    }

    fun resume() {
        if (running) return
        running = true
        // 重新开始计时，暂停期间的时间不计入动画
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)

        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
                .coerceAtMost(MAX_FRAME_DELTA_SECONDS)
        }
        lastFrameNanos = frameTimeNanos
        updateAnimation(deltaSeconds)
        updatePopIn(deltaSeconds)

        val swapChain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(swapChain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    /**
     * 每帧先推进状态机（取消点 / 100% 两个检查点的切换都在这里发生），
     * 再按状态机给出的动作和时间摆骨骼，保证渲染的永远是切换后的状态。
     */
    private fun updateAnimation(deltaSeconds: Float) {
        comboStateMachine.update(deltaSeconds)

        val currentIndex = animationIndex.getValue(comboStateMachine.currentMove)
        animator.applyAnimation(currentIndex, comboStateMachine.currentTime)

        if (fadeFromIndex >= 0) {
            if (fadeElapsed < CROSS_FADE_SECONDS) {
                // 上一段动画继续往前走，alpha 从 0 升到 1，姿势逐渐过渡到当前动画
                val fromTime = clampToClip(fadeFromIndex, fadeFromTime + fadeElapsed)
                animator.applyCrossFade(fadeFromIndex, fromTime, fadeElapsed / CROSS_FADE_SECONDS)
                fadeElapsed += deltaSeconds
            } else {
                fadeFromIndex = -1
            }
        }

        animator.updateBoneMatrices()
    }

    private fun onMoveChanged(from: Move, fromTime: Float, to: Move) {
        Log.d(TAG, "动作切换: $from(${"%.2f".format(fromTime)}s) -> $to")
        fadeFromIndex = animationIndex.getValue(from)
        fadeFromTime = fromTime
        fadeElapsed = 0f
    }

    /** idle 循环播放，时间可以随便往前走；一次性的连招动画则停在末帧。 */
    private fun clampToClip(index: Int, time: Float): Float {
        if (index == animationIndex.getValue(Move.IDLE)) return time
        return time.coerceAtMost(animator.getAnimationDuration(index) - CLIP_END_EPSILON)
    }

    fun destroy() {
        pause()
        uiHelper.detach()

        assetLoader.destroyAsset(asset)
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        assetLoader.destroy()
        resourceLoader.destroy()

        engine.destroyEntity(sunEntity)
        engine.destroyIndirectLight(indirectLight)
        skybox?.let { engine.destroySkybox(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(sunEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            surfaceView?.display?.let { displayHelper?.attach(renderer, it) }
        }

        override fun onDetachedFromSurface() {
            displayHelper?.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                // 等 GPU 用完这个 SwapChain 再让 Surface 被系统回收
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(CAMERA_FOV_DEGREES, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            view.viewport = Viewport(0, 0, width, height)
        }
    }
}
