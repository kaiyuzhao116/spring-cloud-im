/**
 * 公共模块：统一响应体、业务异常、用户上下文、工具类。
 * 约定：不依赖任何业务模块，也不引入 web 依赖（保证 WebFlux 网关与 Netty 模块可安全复用）。
 */
package com.song.common;
