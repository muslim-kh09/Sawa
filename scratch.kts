import java.nio.ByteBuffer

val buf = ByteBuffer.allocate(10)
buf.putShort(5)
buf.flip()
val s = buf.short
println("Short: $s")
