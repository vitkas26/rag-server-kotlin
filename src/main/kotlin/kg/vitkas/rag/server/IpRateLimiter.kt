package kg.vitkas.rag.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Официальный io.ktor:ktor-server-rate-limit регистрирует свой интерцептор на фазе, которая
// исполняется ПОСЛЕ фазы Authentication (эмпирически подтверждено: 401-ответы никогда не получали
// X-RateLimit-* заголовки и не учитывались в счётчике, тогда как успешные 200 — учитывались).
// Это означает, что неограниченное число неверных Basic Auth попыток не троттлится вообще —
// дыра для брутфорса пароля. Ручной интерцептор на фазе ApplicationCallPipeline.Plugins
// гарантированно выполняется до фазы Authentication, поэтому лимит применяется к каждому
// запросу независимо от результата аутентификации.
private data class Bucket(val windowStartMs: Long, val count: AtomicInteger)

class IpRateLimiter(private val limit: Int, private val windowMs: Long) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.compute(key) { _, existing ->
            if (existing == null || now - existing.windowStartMs >= windowMs) {
                Bucket(now, AtomicInteger(0))
            } else {
                existing
            }
        }!!
        return bucket.count.incrementAndGet() <= limit
    }
}
