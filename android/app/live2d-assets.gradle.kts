import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.imageio.ImageIO

// Core is proprietary: never put it or owner assets under src/. See docs/dev-setup.md.
val localModelProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val modelPath = providers.gradleProperty("vtubephone.modelDir").orNull
    ?: localModelProperties.getProperty("vtubephone.modelDir", "")
val textureLimit = (providers.gradleProperty("vtubephone.maxTextureSize").orNull
    ?: localModelProperties.getProperty("vtubephone.maxTextureSize", "4096")).toIntOrNull()
val coreUrl = "https://cubism.live2d.com/sdk-web/core/05/live2dcubismcore.min.js"
val coreHash = "25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f"

tasks.named("prepareLive2dAssets") {
    val output = layout.buildDirectory.dir("generated/live2d/assets")
    outputs.dir(output)
    // Retry failed network/model preparation on the next build, including after edits to local.properties.
    outputs.upToDateWhen { false }
    doLast {
        val root = output.get().asFile
        project.delete(root)
        root.mkdirs()
        val manifest = linkedMapOf<String, Any?>("model" to null, "core" to null)
        fun json(file: File, value: Any?) {
            file.parentFile.mkdirs()
            file.writeText(JsonOutput.toJson(value), Charsets.UTF_8)
        }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        try {
            val cached = layout.buildDirectory.file("live2d-cache/live2dcubismcore.min.js").get().asFile
            if (!cached.isFile || digest(cached) != coreHash) {
                check(!gradle.startParameter.isOffline) { "Core cache unavailable in --offline mode" }
                cached.parentFile.mkdirs()
                val connection = URI(coreUrl).toURL().openConnection().apply {
                    connectTimeout = 15000; readTimeout = 30000
                }
                val temporary = File(cached.parentFile, "core.download")
                try {
                    connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(digest(temporary) == coreHash) { "Cubism Core SHA-256 mismatch" }
                    temporary.copyTo(cached, overwrite = true)
                } finally { temporary.delete() }
            }
            cached.copyTo(root.resolve("web/vendor/live2dcubismcore.min.js").apply { parentFile.mkdirs() })
            manifest["core"] = "vendor/live2dcubismcore.min.js"
        } catch (e: Exception) {
            logger.warn("Live2D Core unavailable: ${e.message}")
            manifest["coreError"] = "Cubism Core を取得できませんでした"
        }
        val target = root.resolve("models/default")
        try {
            check(modelPath.isNotBlank() && file(modelPath).isDirectory) { "モデル未設定" }
            val source = file(modelPath).canonicalFile
            check(textureLimit != null && textureLimit > 0) { "maxTextureSize must be a positive integer" }
            val limit = textureLimit!!
            val settings = source.listFiles()!!.filter { it.name.endsWith(".model3.json") }
            check(settings.size == 1) { "Expected exactly one model3.json" }
            @Suppress("UNCHECKED_CAST")
            val model = JsonSlurper().parse(settings.single(), "UTF-8") as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val refs = model["FileReferences"] as MutableMap<String, Any?>
            fun sourceFile(name: String): File {
                val path = source.resolve(name).canonicalFile
                check(path.toPath().startsWith(source.toPath()) && path.isFile) { "Missing/invalid model asset: $name" }
                return path
            }
            fun copy(name: String) {
                val original = sourceFile(name)
                original.copyTo(target.resolve(name).apply { parentFile.mkdirs() }, overwrite = true)
            }
            copy(refs["Moc"] as String)
            for (key in listOf("Physics", "Pose", "UserData", "DisplayInfo")) {
                (refs[key] as? String)?.let { copy(it) }
            }
            val dimensions = mutableListOf<Map<String, Any>>()
            for (name in refs["Textures"] as List<*>) {
                name as String
                val original = sourceFile(name)
                // Decode at a subsampling factor first: avoid allocating a full 8192² ARGB image.
                ImageIO.createImageInputStream(original).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    check(readers.hasNext()) { "Cannot decode texture: $name" }
                    val reader = readers.next()
                    try {
                        reader.input = input
                        val width = reader.getWidth(0); val height = reader.getHeight(0)
                        val ratio = minOf(1.0, limit.toDouble() / maxOf(width, height))
                        val w = maxOf(1, (width * ratio).toInt()); val h = maxOf(1, (height * ratio).toInt())
                        val param = reader.defaultReadParam
                        val sampling = maxOf(1, maxOf(width, height) / limit)
                        param.setSourceSubsampling(sampling, sampling, 0, 0)
                        val decoded = reader.read(0, param)
                        val resized = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        val graphics = resized.createGraphics()
                        try {
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            graphics.drawImage(decoded, 0, 0, w, h, null)
                        } finally { graphics.dispose(); decoded.flush() }
                        try {
                            val dest = target.resolve(name).apply { parentFile.mkdirs() }
                            check(ImageIO.write(resized, "png", dest)) { "Cannot encode texture: $name" }
                        } finally { resized.flush() }
                        dimensions += mapOf("file" to name, "width" to w, "height" to h)
                    } finally { reader.dispose() }
                }
            }
            // This owner's exp3 files aren't listed in model3.json. Expose them by file stem too.
            val expressions = source.walkTopDown().filter { it.isFile && it.name.endsWith(".exp3.json") }.map {
                val name = it.relativeTo(source).invariantSeparatorsPath
                copy(name)
                mapOf("Name" to it.name.removeSuffix(".exp3.json"), "File" to name)
            }.toList()
            val declared = (refs["Expressions"] as? List<*>) ?: emptyList<Any>()
            declared.forEach {
                @Suppress("UNCHECKED_CAST") val expression = it as Map<String, Any?>
                copy(expression["File"] as String)
            }
            refs["Expressions"] = (declared + expressions).distinctBy { (it as Map<*, *>)["Name"] }
            @Suppress("UNCHECKED_CAST")
            (refs["Motions"] as? Map<String, List<Map<String, Any?>>>)?.values?.flatten()?.forEach {
                copy(it["File"] as String)
                (it["Sound"] as? String)?.let { sound -> copy(sound) }
            }
            json(target.resolve("model3.json"), model)
            manifest["model"] = "../models/default/model3.json"
            manifest["textures"] = dimensions
        } catch (e: Exception) {
            project.delete(target)
            logger.warn("Live2D model unavailable: ${e.message}")
            manifest["modelError"] = if (modelPath.isBlank() || !file(modelPath).isDirectory) "モデル未設定"
                else "モデルの取り込みに失敗しました（テクスチャ・参照ファイルを確認してください）"
        }
        json(root.resolve("web/live2d-assets.json"), manifest)
    }
}
