# Microservice Job App (Spring Boot)

Dự án là một hệ thống tuyển dụng dạng microservice, gồm quản lý công việc (Job), công ty (Company), đánh giá (Review), kèm các thành phần hạ tầng như Service Registry (Eureka), API Gateway và Config Server.

## 1. Kiến trúc tổng quan

Hệ thống gồm 6 service:

- `service-reg`: Eureka Server để đăng ký/tra cứu service
- `configserver`: Spring Cloud Config Server (đọc config từ Git)
- `gateway`: API Gateway (Spring Cloud Gateway)
- `companyms`: quản lý công ty
- `jobms`: quản lý job, tổng hợp dữ liệu company + review
- `reviewms`: quản lý review theo công ty

Luồng chính:

- Client gọi vào `gateway` (port 8084)
- Gateway route sang các service qua Eureka (`lb://...`)
- `jobms` gọi `companyms` + `reviewms` bằng OpenFeign để trả về DTO tổng hợp
- `reviewms` publish message RabbitMQ khi tạo review
- `companyms` consume message, gọi lại `reviewms` để tính average rating và cập nhật rating công ty

## 2. Công nghệ sử dụng

- Java 17
- Spring Boot 3.3.1
- Spring Cloud 2023.0.2
- Spring Data JPA
- PostgreSQL (runtime)
- Eureka (Netflix Service Discovery)
- Spring Cloud Gateway
- Spring Cloud OpenFeign
- Spring Cloud Config Server
- Resilience4j (RateLimiter/CircuitBreaker annotation)
- RabbitMQ (AMQP)
- Actuator + Micrometer Tracing + Zipkin
- Maven Wrapper (`mvnw`, `mvnw.cmd`)

## 3. Cấu trúc thư mục

```text
companyms/
configserver/
gateway/
jobms/
reviewms/
service-reg/
```

Mỗi module là một Spring Boot app độc lập, có `pom.xml`, `src/main`, `src/test`, `mvnw` riêng.

## 4. Cổng chạy và tên service

Theo các file `application.properties` hiện tại:

- `service-reg`: `8761` (`spring.application.name=service-registry`)
- `gateway`: `8084` (`spring.application.name=gateway`)
- `companyms`: `8081` (`spring.application.name=company-service`)
- `jobms`: `8082` (`spring.application.name=job-service`)
- `reviewms`: `8083` (`spring.application.name=review-service`)
- `configserver`: chưa set port trong local file, mặc định Spring Cloud Config Server thường là `8888`

Lưu ý quan trọng:

- `jobms` có `spring.config.import=optional:configserver:http://localhost:8080`.
- Route của gateway đang trỏ `lb://JOB-SERVICE-DEV` cho `/jobs/**`.

Điều này cho thấy dự án đang kỳ vọng có config từ Git repo ngoài (`configserver`) để override một số giá trị theo profile `dev`. Nếu chạy thuần local theo file hiện tại, cần đồng bộ lại các giá trị này để route đúng service.

## 5. Điều kiện trước khi chạy

Cần cài sẵn:

- JDK 17
- Maven (hoặc dùng `mvnw`)
- PostgreSQL
- RabbitMQ
- (tuỳ chọn) Zipkin để xem tracing

Tạo sẵn 3 database PostgreSQL:

- `company`
- `job`
- `review`

Mặc định source đang dùng:

- username: `postgres`
- password: `password`

Nếu khác môi trường, sửa lại trong từng `application.properties` hoặc config repo của Config Server.

## 6. Thứ tự khởi động local

Khởi động theo thứ tự để tránh lỗi discovery/config:

1. `service-reg`
2. `configserver`
3. `companyms`
4. `reviewms`
5. `jobms`
6. `gateway`

### Chạy từng module (Windows)

Ví dụ với một module (lặp lại tương tự cho các module khác):

```powershell
cd service-reg
.\mvnw.cmd spring-boot:run
```

Hoặc build jar rồi chạy:

```powershell
cd service-reg
.\mvnw.cmd clean package
java -jar target\*.jar
```

## 7. API chính

### 7.1 Company Service (`companyms`)
Base path: `/companies`

- `GET /companies` - lấy tất cả company
- `GET /companies/{id}` - lấy company theo id
- `POST /companies` - tạo company
- `PUT /companies/{id}` - cập nhật company
- `DELETE /companies/{id}` - xoá company

Ví dụ body tạo company:

```json
{
  "name": "EmbarkX",
  "description": "Tech company"
}
```

### 7.2 Job Service (`jobms`)
Base path: `/jobs`

- `GET /jobs` - lấy danh sách job dạng `jobDTO` (gồm job + company + review)
- `GET /jobs/{id}` - lấy job theo id (dạng DTO)
- `POST /jobs` - tạo job
- `PUT /jobs/{id}` - cập nhật job
- `DELETE /jobs/{id}` - xoá job

Ví dụ body tạo job:

```json
{
  "title": "Java Backend Engineer",
  "description": "Build microservices",
  "minSalary": "1000",
  "maxSalary": "2500",
  "location": "HCM",
  "companyId": 1
}
```

### 7.3 Review Service (`reviewms`)
Base path: `/reviews`

- `GET /reviews?companyId={companyId}` - lấy review theo công ty
- `POST /reviews?companyId={companyId}` - tạo review
- `GET /reviews/{reviewId}` - lấy review theo id
- `PUT /reviews/{reviewId}` - cập nhật review
- `DELETE /reviews/{reviewId}` - xoá review
- `GET /reviews/averageRating?companyId={companyId}` - tính điểm trung bình

Ví dụ body tạo review:

```json
{
  "title": "Great place",
  "description": "Good culture",
  "rating": 4.5
}
```

## 8. Gọi API qua Gateway

Gateway route hiện tại:

- `/companies/**` -> `COMPANY-SERVICE`
- `/jobs/**` -> `JOB-SERVICE-DEV`
- `/reviews/**` -> `REVIEW-SERVICE`

Ví dụ call qua gateway (port 8084):

- `GET http://localhost:8084/companies`
- `GET http://localhost:8084/jobs`
- `GET http://localhost:8084/reviews?companyId=1`

Nếu `/jobs/**` lỗi route, kiểm tra lại tên service đăng ký Eureka của `jobms` và route id trong gateway cho khớp.

## 9. Messaging (RabbitMQ)

Queue dùng chung:

- `CompanyRatingQueue`

Luồng event:

1. Tạo review ở `reviewms`
2. `reviewms` gửi message vào `CompanyRatingQueue`
3. `companyms` lắng nghe queue
4. `companyms` gọi `reviewms` lấy average rating và cập nhật vào company

## 10. Monitoring

- Mỗi service có tích hợp Actuator
- Tracing bật sampling `1.0`
- Có dependency Zipkin reporter

Tối thiểu có thể kiểm tra health endpoint của service (tuỳ cấu hình expose endpoint ở từng service).

## 11. Những điểm cần đồng bộ khi chạy thực tế

- Đồng bộ `spring.config.import` của `jobms` với port Config Server thật.
- Đồng bộ route `/jobs/**` trong gateway với tên service đăng ký Eureka thực tế (`job-service` hay `job-service-dev`).
- Cân nhắc đổi `spring.jpa.hibernate.ddl-auto=create-drop` sang `update`/`validate` khi không muốn mất dữ liệu sau mỗi lần restart.

## 12. Kiểm thử

Mỗi module có test class khởi tạo context cơ bản trong `src/test/java/...`:

- `CompanymsApplicationTests`
- `JobmsApplicationTests`
- `ReviewmsApplicationTests`
- `GatewayApplicationTests`
- `ServiceRegApplicationTests`
- `ConfigServerApplicationTests`

---

Nếu cần, có thể mở rộng README thêm phần Docker Compose (Postgres + RabbitMQ + Zipkin + toàn bộ service) để chạy one-command cho local/dev team.