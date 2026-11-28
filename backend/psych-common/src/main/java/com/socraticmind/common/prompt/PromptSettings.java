package com.socraticmind.common.prompt;

/** 读取当前生效的提示词配置。agent / rag 只依赖这个接口，具体落盘在 infrastructure。 */
public interface PromptSettings {

    PromptConfig get();
}
