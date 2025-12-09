这个仓库是 Keiyoushi Extensions 的插件仓库。我们现在在做的工作，是创建(了)一个 `src/all/remoteapi` 插件，这个插件允许用户连接到自建的 python 或任意支持我们标准的 API 服务器。`server/remoteapi_fastapi` 是用 astral uv + python + fastapi 写的一个案例服务器。`server/remoteapi_fastapi/docs` 则包含了相关的 API 文档。


总体的任务是: 基于 `server/remoteapi_fastapi` 代码库，创建 `server/jmcomic_fastapi` 服务器 (复制一份过去，基于代码库开发)，用 jmcomic python 库的移动 API 模式，提供 API 服务器给 kotlin 插件。

实现所有能实现的功能 (能否实现需要考虑 jmcomic 支持的功能和 API 能支持的功能)。

值得注意的是，`server/remoteapi_fastapi` 使用 astral uv + python 3.13 + fastapi + ruff + mypy strict + pytest 开发，使用现代的 pyproject.toml 最佳实践。



