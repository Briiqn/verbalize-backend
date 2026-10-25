package dev.briiqn.verbalize.infrastructure.config

import java.io.File

class EnvConfig(workingDir: File) {
    private val values = HashMap<String, String>()

    init {
        for (candidate in listOf(File(workingDir, "server-kt/.env"), File(workingDir, "server/.env"), File(workingDir, ".env"))) {
            if (!candidate.isFile) continue
            for (line in candidate.readLines()) {
                val m = Regex("^\\s*([A-Z0-9_]+)\\s*=\\s*(.*)\\s*$").find(line) ?: continue
                val key = m.groupValues[1]
                if (!values.containsKey(key)) {
                    values[key] = m.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }
    }

    operator fun get(key: String): String? = System.getenv(key) ?: values[key]

    val port: Int get() = this["PORT"]?.toIntOrNull() ?: 8080
    val geminiApiKey: String? get() = this["GEMINI_API_KEY"]?.ifBlank { null }
    val elevenLabsApiKey: String? get() = this["ELEVENLABS_API_KEY"]?.ifBlank { null }
    val geminiModel: String get() = this["GEMINI_MODEL"] ?: "gemini-3.1-flash-lite"
    val distDir: File get() = File(this["VERBALIZE_DIST"] ?: "dist")
    val dbUrl: String get() = this["DB_URL"] ?: "jdbc:mariadb://127.0.0.1:3306/verbalize"
    val dbUser: String get() = this["DB_USER"] ?: "root"
    val dbPassword: String get() = this["DB_PASSWORD"] ?: "verbalize-local"
}
