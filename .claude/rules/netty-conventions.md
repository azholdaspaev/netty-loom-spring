# Netty Conventions

## EventLoop Safety
- **NEVER** block an EventLoop thread (no `Thread.sleep`, no blocking I/O, no `synchronized`)
- Offload blocking work to a virtual thread executor:
  ```java
  var executor = Executors.newVirtualThreadPerTaskExecutor();
  ctx.channel().eventLoop().execute(() -> { /* non-blocking only */ });
  executor.submit(() -> { /* blocking OK here */ });
  ```

## ByteBuf Lifecycle
- **Retain before offload:** Call `msg.retain()` before dispatching to a virtual thread
- **Release in finally:** Always release in a `finally` block
  ```java
  buf.retain();
  executor.submit(() -> {
      try {
          process(buf);
      } finally {
          buf.release();
      }
  });
  ```
- `SimpleChannelInboundHandler` auto-releases; `ChannelInboundHandlerAdapter` does NOT

## Handler Annotations
- `@Sharable` for stateless, thread-safe handlers
- Non-`@Sharable` handlers must be created per-channel

## Leak Detection
- Tests run with `-Dio.netty.leakDetectionLevel=paranoid`
- CI must pass with paranoid leak detection

## Pipeline Design
- Keep handlers small and single-purpose
- Use `ChannelInitializer` to assemble pipelines
- Document handler ordering in pipeline
