package com.zyblw.agent.workspace;

/**
 * JDK 进程运行器的测试子进程。
 *
 * <p>该类只依赖 JDK，不依赖 shell、Docker 或宿主 PATH，因此 CI、macOS 和 Linux 都能用相同方式验证输出截断、
 * 非零退出码与硬超时。</p>
 */
public final class SandboxProcessStub {
  private SandboxProcessStub() {}

  /**
   * @param args 第一个参数是模式：output 产生 stdout/stderr 并以 7 退出；slow 长时间休眠供超时测试使用
   */
  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "output" : args[0];
    if ("slow".equals(mode)) {
      Thread.sleep(60_000L);
      return;
    }
    System.out.print("abcdefgh");
    System.err.print("ABCDEFGH");
    System.out.flush();
    System.err.flush();
    System.exit(7);
  }
}
