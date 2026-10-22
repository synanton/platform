---
translated-from: 1dce05c418d4a92b62c0ed7717728a28218ff760 @ 2026-10-22
source: docs/architecture/synanton-design-1.32.md
pilot-sections: "55-62"
translation-status: pilot
---

# 55. Ресурс Operation

Единый ресурс операции задаёт общий асинхронный контракт.

```http
GET /v1/operations/{operation_id}
```

Пример:

```json
{
  "operation_id": "op_01...",
  "status": "RUNNING",
  "type": "INGESTION",
  "progress": {
    "completed": 73,
    "total": 100
  }
}
```

> **Граница с Design 1.27:** ресурс Operation — это внешний контракт публичного API для длительной работы: долговечный версионируемый интерфейс, который клиент опрашивает или на который подписывается. Он отличается от внутренних семантик событий, команд и рабочих процессов в Design 1.27 и не дублирует их (очереди, повторы, конечные автоматы саг и рабочих процессов, гарантии доставки брокера). Design 1.27 определяет внутреннюю координацию и восстановление работы; Design 1.32 определяет только то, что клиент наблюдает через `/v1/operations/{operation_id}`. См. также §85–§87 (API и Eventing; внутренние и внешние события) и §163 (линия происхождения событий публичного API).

---

# 56. Состояния Operation

Начальные состояния:

```text
ACCEPTED
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCEL_REQUESTED
CANCELLED
EXPIRED
```

---

# 57. Ошибка Operation

Для операции, завершившейся ошибкой, следует предоставлять структурированные сведения об ошибке:

```json
{
  "status": "FAILED",
  "error": {
    "code": "SOURCE_ACCESS_DENIED",
    "message": "The source could not be accessed.",
    "retryable": false
  }
}
```

Сообщения не должны раскрывать секреты или конфиденциальные сведения о внутренних компонентах.

---

# 58. Длительные операции

Для работы, которая может превысить обычный бюджет времени запроса, API следует быстро возвращать ответ.

Примеры:

```text
ingestion
reindex
recalculation
bulk processing
AI execution
export
```

---

# 59. Отмена

Запрос на отмену не обязательно сразу переводит операцию в конечное состояние.

Пример:

```http
POST /v1/operations/{id}/cancel
```

Состояние операции может изменяться так:

```text
RUNNING
  ↓
CANCEL_REQUESTED
  ↓
CANCELLED
```

---

# 60. Повторные попытки

Клиентам следует различать:

```text
HTTP request retry
```

и:

```text
operation retry
```

Истечение времени ожидания HTTP-запроса не обязательно означает, что операция завершилась ошибкой.

Клиентам следует запрашивать состояние операции по ключу идемпотентности или идентификатору операции.

---

# 61. Модель ошибок

В API следует использовать единый формат структурированных ошибок.

Пример:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The request is invalid.",
    "details": [],
    "request_id": "req_01..."
  }
}
```

---

# 62. Категории ошибок

Начальные категории:

```text
VALIDATION_ERROR
AUTHENTICATION_REQUIRED
AUTHORIZATION_DENIED
RESOURCE_NOT_FOUND
RESOURCE_CONFLICT
PRECONDITION_FAILED
RATE_LIMITED
DEPENDENCY_UNAVAILABLE
OPERATION_FAILED
INTERNAL_ERROR
```

---
