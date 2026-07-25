# 自建只读 DB MCP server,数据库用 SQLite

通过 MCP 访问数据库,可消费现成 server(如官方 sqlite MCP server),或自建。我们**自建一个只读 DB MCP server**,数据库用 **SQLite**。理由:只消费现成 server 只练了 MCP 的客户端半套;自建才练到服务端(工具定义、协议、传输),且把只读强制放在 server 边界最干净。SQLite 零基础设施、JDBC 即可、便于塞示例数据。
