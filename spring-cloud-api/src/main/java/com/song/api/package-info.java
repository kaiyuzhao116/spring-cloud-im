/**
 * 契约模块：DTO、Dubbo 接口、事件定义。
 * 约定：
 * 1. 只放契约，不放实体与业务实现；
 * 2. 接口只增不改，改签名必须升 version；
 * 3. 子包按域划分：auth / user / im / account。
 */
package com.song.api;
