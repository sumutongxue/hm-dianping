# 黑马点评项目调试复盘

**日期**：2026 年 10 月 28 日　|　**工程**：hm-dianping（Spring Boot 2.3.12）　|　**主线**：从"测试跑不起来"到"成功发出一张秒杀券"

---

## 一、今天踩过的七个坑

| 序号 | 现象 | 真正的原因 |
| --- | --- | --- |
| 1 | 测试类没有绿色三角，提示 Annotate method as @Test | 测试类不是 public；同时 IDEA 后台索引没跑完 |
| 2 | 误以为 junit 依赖缺失 | 依赖一直在，红的是编辑器的解析结果 |
| 3 | 不知道 MySQL 数据存哪 | 在 MySQL 服务端数据目录，不在工程目录里 |
| 4 | IDEA 内置 Database 连不上库 | 驱动 jar 没下载成功 |
| 5 | 接口返回 401 Unauthorized + 339 B | 请求体类型是 text/plain，415 被 /error 二次拦截成 401 |
| 6 | 登录接口返回 200 但"服务器异常" | 请求根本没带 body |
| 7 | 不知道登录态怎么带 | 验证码 → token → 请求头 authorization |

---

## 二、环境速查

| 组件 | 版本 / 地址 | 位置 |
| --- | --- | --- |
| 应用 | Spring Boot 2.3.12，端口 8081 | F:\develop\idea_workspace\hm-dianping |
| 反向代理 | nginx 1.18.0，监听 8080 | F:\develop\nginx-1.18.0\nginx-1.18.0 |
| 数据库 | MySQL 8.0.26（服务名 MySQL80） | C:\ProgramData\MySQL\MySQL Server 8.0\Data |
| 缓存 | Redis，192.168.64.130:6379（密码 222543） | 虚拟机 |
| IDE | IntelliJ IDEA Ultimate 2021.2.1 | F:\develop\IDEA\IntelliJ IDEA 2021.2.1 |
| JDK | 17.0.18（与本项目应使用的 JDK 8 不符） | F:\develop\JDK |

浏览器访问的 `localhost:8080/api/...` 会先到 nginx，由 `rewrite /api(/.*) $1` 去掉前缀后转发到 8081，所以后端看到的路径没有 `/api`。

---

## 三、逐个问题复盘

### 3.1 测试类点不动：没有绿色三角

**现象**：`HmDianPingApplicationTests` 里写了 `@Test`，IDEA 却不给运行三角，还提示 `Annotate method 'testIdWorker' as @Test`。

**排查**：这条提示只说明一件事——IDEA 认为 `@Test` 这个符号没被解析到。但 Maven 侧其实是好的：`target/test-classes` 里的 class 常量池含 `Lorg/junit/Test;`，major version 52，说明编译期依赖完整。依赖链是 `spring-boot-starter-test:2.3.12 → junit-vintage-engine:5.6.3 → junit:4.13.2`，注意只有 Spring Boot 2.4 及以后才移除 vintage，这个版本不要升。

**根因**：两个问题叠在一起。其一，测试类声明是 `class` 而不是 `public class`，而 JUnit 4 硬性要求测试类 public，字节码标志位里也确实没有 ACC_PUBLIC。其二，IDEA 后台的 Maven 导入与索引当时没跑完，编辑器里 `@Test` 一直是红的。

**解决**：把类改成 `public class`，等 IDEA 索引结束（或手动点 Maven 面板刷新），三角出现。

**结论**：`import` 是红的，就别先怀疑版本或重装依赖，先看 IDEA 右下角有没有正在跑的任务。

### 3.2 一次被推翻的判断：驱动到底兼不兼容

排查过程中我曾判断"mysql-connector-java 5.1.47 不支持 MySQL 8 的 caching_sha2_password，会报 Unable to load authentication plugin"。后来用 JDBC 直连实测，结果推翻了它：

```text
OK  8.0.26 via mysql-connector-java-5.1.47
tb_user rows = 1013
```

驱动没问题，那条警告作废。**结论：结论要建立在实测上，不要建立在版本直觉上。**

### 3.3 MySQL 的数据到底存在哪里

工程里的 `src/main/resources/db/hmdp.sql`（153 KB）只是建库脚本，不是运行中的数据。真正的数据在 MySQL 服务端的数据目录：

```text
C:\ProgramData\MySQL\MySQL Server 8.0\Data\dainping\
```

该目录下 11 个 `.ibd` 文件对应 11 张表，其中 `tb_user` 1013 行、`tb_shop` 14 行、`tb_shop_type` 10 行、`tb_blog` 4 行，其余为空。注意 `C:\ProgramData` 是隐藏目录，且这些是二进制文件，只能通过 SQL 读写。

备份方式：

```bash
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -uroot -p123456 dainping > dainping_backup.sql
```

### 3.4 IDEA 内置 Database 连不上

**现象**：Test Connection 报 `Driver class 'com.mysql.cj.jdbc.Driver' not found. Driver files are not downloaded`，Schemas 页签显示 Nothing to show。

**根因**：IDEA 的 Database 工具使用独立于项目的驱动，首次使用要联网下载，而 `download.jetbrains.com` 连接被重置。

**解决**：左侧切到 Drivers 页 → 选中 MySQL → Driver Files 区域点 `+` → Custom JARs，指定本地 jar（`F:\develop\jdbc-drivers\mysql-connector-j-9.7.0.jar`），确认 Class 为 `com.mysql.cj.jdbc.Driver` → Apply。之后回数据源 Test Connection，再到 **Schemas 页签勾选 dainping**，表才会显示出来。

**jar 可用性自检**：SHA256 与原件一致；`javap -v` 显示 class major version 52（Java 8 字节码），IDEA 自带的 JBR 11 可以正常加载。

### 3.5 401 Unauthorized 与那个 339 B

**现象**：POST `/api/voucher/seckill` 返回 `401 Unauthorized`，耗时 38 ms，响应 339 B；而这个接口用 curl 直接请求却返回 200。

**排查**：本项目 `LoginInterceptor` 返回 401 时只设状态码、不写 body，`Content-Length` 是 0。所以 339 B 不可能是业务代码产生的——那是 Postman 统计的"响应头 + 响应体"总大小。

**根因**：Postman 的 Body 页签右侧选的是 `Text`，发出的 `Content-Type` 为 `text/plain`。Spring 找不到能把 text/plain 转成 `LoginFormDTO` 的转换器，抛 415；415 转发到内部的 `/error` 路径生成错误响应，而 `/error` 不在拦截器白名单里，被 `LoginInterceptor` 又拦了一次，最终对外显示成 401。

**解决**：Body → raw → 把下拉从 `Text` 改成 `JSON`。

**判别技巧**：

| 401 的样子 | 真实原因 |
| --- | --- |
| 只设状态码，没有 Accept 头 | 真的没登录或 token 失效 |
| 带一长串 `Accept: application/json, ...` | 其实是 415/400，被 /error 转发二次拦截 |

### 3.6 200 却返回"服务器异常"

**现象**：`POST /api/user/login` 返回 HTTP 200，body 是 `{"success":false,"errorMsg":"服务器异常"}`。

**根因**：请求没有 body。Spring 抛 `HttpMessageNotReadableException`（RuntimeException 子类），被 `WebExceptionAdvice` 的 `@ExceptionHandler(RuntimeException.class)` 捕获，统一返回这句文案。

**对照实验**：

```text
不带 body  → {"success":false,"errorMsg":"服务器异常"}
body 为 {} → {"success":false,"errorMsg":"手机号格式错误"}
```

第二行说明"传了 body 但字段为空"和"完全没传 body"是两条不同的路径。

**结论**：这个项目**永远返回 HTTP 200**，业务成败只看 `success` 字段，别被绿色的 200 OK 骗了。

### 3.7 登录链路：验证码 → token → 请求头

项目用 Redis 存登录态，不用 Session。三个步骤：

| 步骤 | 请求 | 服务端行为 |
| --- | --- | --- |
| 1 | POST /api/user/code?phone=手机号 | 生成 6 位码存 `login:code:<phone>`，2 分钟过期，同时打日志模拟发短信 |
| 2 | POST /api/user/login，body 传 phone + code | 比对 Redis 中的码，一致则生成 UUID token，把用户信息存成 hash `login:token:<token>`，返回 token |
| 3 | 之后每个请求带 `authorization: <token>` | RefreshTokenInterceptor 查 Redis 还原用户到 ThreadLocal，并续期 |

实测链路：

```text
① POST /api/user/code?phone=13686869696        → {"success":true}
② Redis: GET login:code:13686869696            → "207523"（TTL 113 秒）
③ POST /api/user/login {phone,code}            → {"success":true,"data":"d1e0...3faa"}
④ GET /api/user/me 无 token                    → 401，0 B
⑤ GET /api/user/me 带 token                    → 200，{"id":1,"nickName":"小鱼同学"}
```

**三个坑**：验证码只有 2 分钟，必须发完立刻登录；`authorization` 是全小写、值前面没有 `Bearer`；验证码字符串末尾多一个空格就会比对失败。

### 3.8 秒杀券：发券成功，抢券还没写

| 环节 | 接口 | 状态 |
| --- | --- | --- |
| 商家发券 | POST /api/voucher/seckill | 已成功，入库为 id=10、库存 100 |
| 用户抢券 | POST /api/voucher-order/seckill/10 | 代码里是空实现，返回"功能未完成" |

发券参数有三个易错点：`type` 必须是 1（0 是普通券，不会进 `tb_seckill_voucher`）；`shopId` 必须真实存在；时间格式 `yyyy-MM-ddTHH:mm:ss`，中间的 T 不能省。

另外两张券的活动时间写的是 2022 年，早已过期。等下单逻辑写完，时间校验会先把它拦掉，必须改成当前时间：

```sql
UPDATE tb_seckill_voucher
SET begin_time = '2026-10-28 00:00:00', end_time = '2026-10-31 23:59:59'
WHERE voucher_id = 10;
```

还要注意：`POST /voucher/seckill` 是**新增**接口，改了 body 重发只会多一条新券，不会修改已有那条，要改时间只能走 SQL。

---

## 四、接口鉴权与返回规则速查

| 情况 | 表现 | 说明 |
| --- | --- | --- |
| 白名单接口 | 正常 200 | /shop/**、/voucher/**、/shop-type/**、/upload/**、/blog/hot、/user/code、/user/login |
| 未登录访问受限接口 | 401，body 为 0 字节 | LoginInterceptor 直接拦截 |
| 业务失败 | 200 + success:false | 例如验证码错误、秒杀已结束 |
| 请求体格式错误 | 415 被 /error 转成 401 | 带 Accept 头，容易误判 |
| 运行时异常 | 200 + "服务器异常" | WebExceptionAdvice 兜底 |

---

## 五、遗留待办

1. 装 JDK 8 并把 Project SDK 切回 1.8。Spring Boot 2.3.12 配 Spring 5.2，在 JDK 17 下跑上下文容易出反射与 CGLIB 问题。
2. `application.yaml` 的 `serverTimezone=UTC` 改成 `Asia/Shanghai`，否则写入时间与北京时间差 8 小时。
3. 统一 token 有效期单位：登录处用 `MINUTES`、续期处用 `SECONDS`，都传 36000，实际一个 25 天、一个 10 小时。
4. 把 `voucher_id=10` 的秒杀时间改到当前。
5. 实现秒杀下单：库存校验、一人一单、乐观锁扣减、事务落单，再考虑 Lua 与异步队列。
6. 虚拟机 Redis 未设 `maxmemory`（当前为 0），写满会直接 OOM 失败，建议 256mb + allkeys-lru。

---

## 六、可复用的排查手法

1. **用字节码验证依赖**：`javap -v` 看常量池里有没有目标类，比盯着 pom 猜可靠。
2. **401 先看有没有 Accept 头**：有就是别的东西被二次拦截，没有才是真的没登录。
3. **HTTP 200 不等于成功**：统一封装的项目里，成败看 `success`。
4. **curl 复现排除工具干扰**：Postman 的 Body 类型、代理、编码都可能制造假象。
5. **迁移数据先查 datadir**：`select @@datadir` 一句话就能定位，不要凭经验找目录。
6. **报错先分三层**：我的代码 / 工具配置 / 网络环境，逐层排除比逐行读代码快。

---

*本文档由当天的调试过程整理而成，所有结论均经过实际命令验证；被推翻的判断也一并保留，作为排查思路的对照。*
