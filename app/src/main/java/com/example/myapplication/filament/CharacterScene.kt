package com.example.myapplication.filament

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.myapplication.combo.Move
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
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

/**
 * 基础 Filament 场景：Engine / Renderer / Scene / View / Camera / Light，
 * 加载 glb 角色模型，并用 Choreographer 驱动逐帧渲染和骨骼动画。
 *
 * 所有方法都在主线程调用。
 */
class CharacterScene(context: Context, modelAssetPath: String) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "CharacterScene"

        /** 单帧时间步长上限，避免卡顿或从后台恢复时动画一下子跳过一大截。 */
        private const val MAX_FRAME_DELTA_SECONDS = 0.1f

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
    private val skybox: Skybox

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val asset: FilamentAsset
    private val animator: Animator

    /** 每个动作在 glb 中的动画索引，按名字查出来的。 */
    private val animationIndex: Map<Move, Int>

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var displayHelper: DisplayHelper? = null
    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L

    private var idleTime = 0f

    init {
        view.scene = scene
        view.camera = camera

        skybox = Skybox.Builder().color(0.12f, 0.12f, 0.14f, 1.0f).build(engine)
        scene.skybox = skybox

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
        scene.addEntities(asset.entities)
        fitIntoUnitCube(asset)

        animator = asset.instance.animator
        animationIndex = findAnimationIndices(animator)

        camera.lookAt(
            0.0, 0.0, 5.5,
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

    /** 把模型缩放、平移到以原点为中心、边长为 2 的立方体内，方便摆相机。 */
    private fun fitIntoUnitCube(asset: FilamentAsset) {
        val box = asset.boundingBox
        val center = box.center
        val halfExtent = box.halfExtent
        val maxExtent = 2.0f * max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
        val scale = 2.0f / maxExtent
        // 列主序矩阵：先平移到原点，再统一缩放
        val transform = floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            -center[0] * scale, -center[1] * scale, -center[2] * scale, 1f,
        )
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), transform)
    }

    fun attachTo(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        displayHelper = DisplayHelper(surfaceView.context)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
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

        val swapChain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(swapChain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun updateAnimation(deltaSeconds: Float) {
        val idle = animationIndex.getValue(Move.IDLE)
        idleTime = (idleTime + deltaSeconds) % animator.getAnimationDuration(idle)
        animator.applyAnimation(idle, idleTime)
        animator.updateBoneMatrices()
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
        engine.destroySkybox(skybox)
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
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            view.viewport = Viewport(0, 0, width, height)
        }
    }
}
