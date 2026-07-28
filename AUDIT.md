# Auditoria do Caskara — 27/07/2026

Revisão completa de `src/main/java/com/cookie/caskara` (~3.100 linhas), docs e build.

**Legenda:** ✅ corrigido nesta auditoria · ⚠️ reportado, exige decisão sua (migração de dados ou quebra de API)

> ⚠️ **Nada foi compilado.** O sandbox tem apenas Java 11 (JRE, sem `javac`) e não tem rede para
> baixar o Gradle, enquanto o projeto exige toolchain 25. Todas as correções foram revisadas
> manualmente. **Rode `./gradlew test` antes de commitar.**

---

## 🔴 Crítico — perda ou corrupção silenciosa de dados

### 1. ✅ `Caskara.save(obj, ttlMillis)` fazia todo registro expirar em 1970

`Caskara.java`

```java
// ANTES
public static <T> String save(T object, long ttlMillis) {
    return core(...).preserve(null, object, ttlMillis);
}
```

O 3º parâmetro de `preserve` é um **timestamp absoluto**, não uma duração. `save(obj, 5000)`
gravava `expires_at = 5000` (epoch, 1º de janeiro de 1970). Toda leitura filtra por
`expires_at > now`, então o registro sumia imediatamente — sem erro, sem log.

Comparação que confirma a inconsistência: `save(T, Duration)` fazia `now + millis` (certo) e
`saveAsync(T, long)` também (certo). Só essa sobrecarga estava errada — e `DOCS.md:252` a
documenta como "same as above using milliseconds", ou seja, a intenção sempre foi duração.

O próprio `CaskaraAdminLogicTest:36` usa essa sobrecarga.

**Corrigido:** agora calcula `System.currentTimeMillis() + ttlMillis`; `ttlMillis <= 0` vira "sem TTL".

### 2. ✅ `@TTL` sem argumentos apagava tudo

`Core.java`, parsing de anotações

```java
this.defaultTtlMillis = (ttl.minutes() * 60_000L) + (ttl.seconds() * 1000L);
```

Ambos os atributos têm `default 0`. Um `@TTL` puro dava `defaultTtlMillis = 0` →
`expiresAt = now + 0` → expirado no mesmo instante em que foi gravado.

**Corrigido:** só aplica TTL se `millis > 0`; caso contrário loga um aviso e ignora a anotação.

### 3. ✅ Migração de schema gravava texto puro em entidades `@Encrypted`

`Core.applyMigrations`

Depois de rodar os migradores, o método persistia o resultado com
`pstmt.setString(1, finalJson)` — **sem passar por `encrypt()`**. Ou seja: na primeira leitura
de um registro `@Encrypted` desatualizado, o JSON era regravado em claro no `.db`. Vazamento
silencioso + o registro passava a ser ilegível na próxima leitura (o `decrypt` devolveria lixo).

**Corrigido:** `pstmt.setString(1, encrypt(finalJson))`.

### 4. ⚠️ `id` é PRIMARY KEY global — entidades de tipos diferentes se sobrescrevem

`Shell.initConnection`

```sql
CREATE TABLE IF NOT EXISTS elements (
    id TEXT PRIMARY KEY,
    type TEXT, ...
)
```

`type` é uma coluna comum, não faz parte da chave. Como toda escrita é
`INSERT OR REPLACE ... (id, type, ...)`, salvar um `Player` com id `"steve"` e depois um
`Inventory` com id `"steve"` **destrói o Player**. Todas as leituras filtram por `id AND type`,
então o dado simplesmente some.

Isso atinge qualquer mod que use IDs naturais (nome/UUID do jogador) em mais de uma entidade
dentro do mesmo shell — o caso de uso mais comum que existe.

**Não corrigi**: exige rebuild da tabela e migração dos dados existentes. Correção proposta:

```sql
CREATE TABLE elements_new (
  id TEXT NOT NULL, type TEXT NOT NULL, json TEXT,
  expires_at INTEGER, deleted_at INTEGER, version INTEGER DEFAULT 1,
  PRIMARY KEY (id, type)
);
INSERT INTO elements_new SELECT id, type, json, expires_at, deleted_at, version FROM elements;
DROP TABLE elements;  ALTER TABLE elements_new RENAME TO elements;
```

Precisa rodar dentro de uma transação, com backup automático antes e um marcador de versão de
schema no próprio arquivo (ex.: `PRAGMA user_version`) para não reexecutar. Posso implementar se
você quiser.

### 5. ⚠️ `autoMigrateLegacyData()` altera um `default.db` que pode estar aberto

`Core.java`, chamado no **construtor de todo Core** de shell não-default.

Ele abre uma **segunda conexão JDBC** para `default.db`, copia as linhas do tipo e depois roda
`DELETE FROM elements WHERE type = ?` lá. Só que `default.db` normalmente é o shell padrão do mod
— possivelmente aberto pelo Caskara neste exato momento, com WAL ativo. Duas conexões escrevendo
no mesmo arquivo, uma delas fora do `ReentrantLock` do Shell, com o cache LRU do Core do
`default.db` intacto apontando para linhas que acabaram de ser removidas.

Sugestão: rodar isso só uma vez de forma explícita (`Caskara.migrateLegacy()`), nunca no construtor,
e reutilizar o `Shell` já aberto em vez de abrir uma conexão paralela.

---

## 🟠 Alto — deadlocks, corridas e resultados errados

### 6. ✅ `tx.load()` dentro de uma transação sempre falhava (deadlock)

`Core.extract` despachava a leitura para outra thread:

```java
CompletableFuture.supplyAsync(() -> shell.runInLock(...), shell.getExecutor())
```

`Transaction.load()` → `extract().sync()`. Mas `Shell.transaction()` já segura o `ReentrantLock`
**na thread chamadora**, que agora fica bloqueada em `Pearl.sync()` esperando uma thread que
tenta pegar esse mesmo lock. Deadlock garantido até o timeout de 5s do `Pearl`, que então lança
`DatabaseException("Operation timed out")`.

Ou seja: **ler dentro de uma transação nunca funcionou**. Nenhum teste cobre isso — `TransactionTest`
só usa `tx.save()` e verifica com `Caskara.load()` fora do bloco.

**Corrigido:** `extract` agora detecta `shell.isLockHeldByCurrentThread()` e faz a leitura inline
na própria thread (o lock é reentrante). Extraí o corpo para `Core.readFromDb(id)` para não duplicar
o SQL. Também trata `executor.isShutdown()`.

### 7. ✅ Transação aninhada dava commit antecipado

`Shell.transaction` fazia `setAutoCommit(false)` + `commit()` sem saber que já estava dentro de outra
transação. A interna dava commit no trabalho da externa — se a externa falhasse depois, o rollback
não desfazia nada. Atomicidade quebrada.

Acontece na prática: `Caskara.saveAll()` abre transação → `tx.save()` → `shell.core()` → construtor
do `Core` → `initializeFts()`/`autoMigrateLegacyData()`, que **chamam `shell.transaction()`**.

**Corrigido:** contador `transactionDepth`; transação aninhada apenas participa da externa.

### 8. ✅ `Shell.core()` fazia I/O dentro de `computeIfAbsent`

O construtor do `Core` roda DDL, abre conexões, executa transações e pode reentrar em `shell.core()`.
`ConcurrentHashMap.computeIfAbsent` proíbe atualização recursiva — lança `IllegalStateException`
("recursive update") ou trava o bin do mapa.

**Corrigido:** double-checked locking com um monitor dedicado (`coreCreationLock`).

### 9. ✅ `Query.fetch()` devolvia objetos com `@Id` nulo e sem migrações

O caminho SQL fazia `Core.getGson().fromJson(rs.getString("json"), clazz)` direto — pulando o
`syncId()` e o `applyMigrations()` que `extract()`/`extractAll()` aplicam. Resultado: os objetos que
saem de `query().fetch()` têm o campo de id **vazio**, e dados em versão antiga de schema voltam
sem migrar. Duas APIs de leitura, dois comportamentos.

**Corrigido:** o `SELECT` agora traz `id, json, version, expires_at` e delega para
`Core.materialize(...)`, o mesmo caminho de `extract()`.

### 10. ✅ Filtro `=` nunca casava com números em cores criptografados

`Query.match` / `getFieldValue`. Cores `@Encrypted` caem no `fetchFromMemory()`, onde
`getFieldValue` normaliza **todo número para `Double`**. Aí:

```java
case "=": return actual.equals(target);   // Double(5.0).equals(Integer(5)) == false
```

`field("level", 5)` nunca retornava nada em entidade criptografada. Mesmo problema em `IN`.

**Corrigido:** novo `equalsValue()` que compara numericamente quando ambos são `Number`, com
fallback para comparação textual.

### 11. ✅ Índice FTS5 acumulava lixo

Toda escrita usa `INSERT OR REPLACE`. No SQLite, o REPLACE apaga a linha antiga **sem disparar os
triggers `AFTER DELETE`** a menos que `recursive_triggers` esteja ligado. Como o `rowid` muda no
REPLACE, o `fts_elements` ficava com a entrada antiga órfã para sempre → `search()` retornando
duplicatas e versões antigas do documento.

**Corrigido:** `PRAGMA recursive_triggers = ON` na inicialização da conexão.

### 12. ✅ `connection` não era `volatile`

Escrito por `initConnection()` e lido pela `asyncWriterThread`, pelo `cleanupScheduler` e pelas
virtual threads do executor — algumas fora do lock (`getConnection()` não trava). Sem barreira de
memória, uma thread podia enxergar `null` ou uma conexão meio-inicializada.

**Corrigido:** campo `volatile`.

### 13. ✅ Vazamento de scheduler a cada reconexão

`Shell.initConnection()` chamava `startCleanupTask()` incondicionalmente, e `getConnection()` chama
`initConnection()` sempre que a conexão está fechada. Cada reconexão criava mais uma thread de
limpeza rodando pra sempre, todas fazendo o mesmo `DELETE`.

**Corrigido:** guarda `if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) return;`.
`Shell.close()` também passou a desligar o `executor` e limpar os caches (antes o executor de virtual
threads ficava vivo).

### 14. ✅ Admin UI deletava sem invalidar o cache

`CaskaraAdminLogic.deleteEntity` fazia `DELETE FROM elements WHERE id = ?` fora do lock do shell e
sem mexer no cache LRU do `Core`. Depois de apagar pela UI, `extract()` continuava devolvendo o
objeto da memória. Também rodava sem `ORDER BY` na paginação (`getShellEntities`), então páginas
podiam repetir ou pular linhas.

**Corrigido:** roda dentro de `shell.runInLock`, chama o novo `Shell.invalidateCaches()`, e a
listagem ganhou `ORDER BY type, id`. `runVacuum` também passou a rodar sob o lock.

> Nota: o `DELETE` continua ignorando a coluna `type` — isso só fica realmente seguro depois de
> resolver o item **#4**.

### 15. ✅ Injeção de SQL em `createIndex`

`Core.createIndex(String jsonField)` é público (`Caskara.createIndex(clazz, campo)`) e interpola o
argumento direto no DDL:

```java
"CREATE INDEX IF NOT EXISTS " + indexName + " ON elements(json_extract(json, '$." + jsonField + "'))..."
```

SQLite não aceita bind de identificador nem de caminho JSON, então o `PreparedStatement` ali não
protege nada. Um campo vindo de config/comando podia injetar SQL.

**Corrigido:** validação com regex (`[A-Za-z_][A-Za-z0-9_]*(\.[...])*`), lançando `ValidationException`.

### 16. ✅ `@Cache(maxSize = 0)` derrubava a aplicação

`new LinkedHashMap<>(0, 0.75f, true)` + `removeEldestEntry: size() > 0` = cache que expulsa tudo;
valor negativo lança `IllegalArgumentException` no construtor do `Core`.

**Corrigido:** clamp com `Math.max(1, newSize)`.

---

## 🟡 Médio — segurança, robustez e correção de API

### 17. ⚠️ A criptografia é bem mais fraca do que os docs diziam

`Core.encrypt/decrypt/generateKey`:

- `Cipher.getInstance("AES")` resolve para **AES-128/ECB/PKCS5Padding**. ECB sem IV: plaintexts
  iguais geram ciphertexts idênticos, então dá pra ver quais registros são iguais só olhando o arquivo.
- Sem tag de autenticação — o ciphertext é maleável, não há verificação de integridade.
- `generateKey` = um único SHA-256 sem salt, truncado em 16 bytes. Nenhum alongamento de chave.
- README, CURSEFORGE e os títulos do DOCS diziam **"AES-256"**, enquanto a tabela do próprio
  DOCS.md (linhas 266/318) dizia AES-128. O código é AES-128.

**Corrigido só a documentação:** textos alinhados para AES-128 e adicionei uma seção de threat model
honesta em `DOCS.md`. **Não mudei o algoritmo** — trocar para AES-GCM torna ilegível todo dado já
gravado. Se quiser fazer, o caminho é um envelope versionado (`v2:<iv>:<ciphertext>`) com fallback
de leitura para o formato antigo e re-gravação preguiçosa.

### 18. ⚠️ `decrypt()` engole falhas e devolve lixo

```java
} catch (Exception e) {
    return data;   // "pode ser dado antigo não criptografado, ou chave errada"
}
```

Chave errada → devolve o Base64 → `applyMigrations` falha no parse → `return null` → a API responde
"não encontrado". Indistinguível de um registro que realmente não existe. Vale distinguir os dois
casos (tentar detectar Base64+padding válido) e no mínimo logar em nível de erro.

### 19. ✅ `rotateKey` podia inutilizar dados permanentemente

`rotateKey` fazia `list(clazz)` com a chave velha e regravava com a nova. Mas `extractAll()`
**descarta silenciosamente** todo registro que não decifrar. Se a chave antiga estivesse errada para
parte dos dados, esses registros não eram regravados — e depois da troca ficavam ilegíveis para
sempre, porque a chave antiga já não estava mais em uso.

**Corrigido:** compara `core.count()` (contagem crua no SQL) com o tamanho da lista decifrada e
**aborta antes de trocar a chave** se houver diferença, restaurando a chave antiga. Isso motivou o
novo método público `Core.count()`.

### 20. ✅ Sem `init()`, o Caskara gravava em caminho relativo silenciosamente

`new File(dataFolder, ...)` com `dataFolder == null` não lança — o Java resolve como caminho
relativo ao cwd do processo. O servidor criava shells em um lugar aleatório sem avisar.

**Corrigido:** `requireInitialized()` lança `IllegalStateException` com mensagem explícita.

### 21. ✅ Dois schedulers concorrentes em `Caskara`

`enableAutoVacuum` e `enableAutoBackup` cada um criava o `scheduler` se fosse `null`, com nomes de
thread diferentes ("Caskara-AutoVacuum" vs "Caskara-Scheduler") — quem chegasse primeiro vencia.
Além disso `shutdown()` zerava o `scheduler` mas deixava os `ScheduledFuture` pendurados, então
reativar depois do shutdown quebrava.

**Corrigido:** `ensureScheduler()` compartilhado, métodos `synchronized`, e `shutdown()` limpa as
duas referências de task.

### 22. ✅ Código de demonstração rodando em produção

`MainPlugin.setup()` chamava `testAdvancedFeatures()` **a cada boot do servidor**: criava entidades
`PlayerData`, registrava observers e chamava
`Caskara.encrypt(PlayerData.class, "hytale-secure-key-123")` — **chave hardcoded no jar publicado**.
Também usava `Caskara.init(File)`, a própria sobrecarga que o projeto marca como `@Deprecated` por
causar colisão de `default.db`.

**Corrigido:** `setup()` agora só inicializa (com namespace `"caskara"`) e registra os comandos.

> Não adicionei o `Caskara.shutdown()` no ciclo de vida do plugin: existe um `shutdown` em
> `PluginBase`, mas não consegui confirmar a assinatura sem descompilar o jar. Vale você conferir
> e sobrescrever — hoje as conexões SQLite nunca são fechadas de forma limpa.

### 23. ✅ Admin UI apontava para shells que não existem

`CaskaraAdminPage` tinha `global.db`, `players.db`, `quests.db`, `economy.db` **hardcoded**, e
começava em `currentShell = "global.db"`. Nenhum desses arquivos existe numa instalação normal — o
shell padrão se chama `<modId>.db`. Na prática a UI abria sempre vazia. O handler de troca também
fazia `eventData.contains("global.db")`, casando por substring.

**Corrigido:** novo `CaskaraAdminLogic.listShellFileNames()` lista os shells realmente abertos; os 4
botões viraram slots por índice, com os que sobram escondidos, e o primeiro shell real é selecionado
por padrão.

### 24. ✅ Paginação com `page(0, n)` gerava OFFSET negativo

`this.offset = (page - 1) * size` — o SQLite rejeita OFFSET negativo. **Corrigido** com `Math.max(0, ...)`.

### 25. ✅ Caminhos aninhados só funcionavam no SQL

O caminho SQL usa `json_extract(json, '$.' || fieldName)`, que entende `"location.x"`. O fallback em
memória (cores criptografados) fazia `json.get(fieldName)`, que devolve `null` para qualquer campo
com ponto. O mesmo filtro dava resultados diferentes conforme a entidade fosse criptografada ou não.

**Corrigido:** `getFieldValue` agora percorre o caminho separado por ponto.

### 26. ✅ `CaskaraLogger` fazia reflection completa a cada log

`Class.forName` + `getMethod` + `invoke` em **toda** chamada de `info/warn/error`, inclusive nos
caminhos de falha em que o logger nem existe. **Corrigido:** resolução única com cache
(double-checked, campos `volatile`).

---

## 🟢 Melhorias sugeridas (não aplicadas)

**API**

- `discard()` e `softDelete()` não disparam observers — só `preserve()` dispara. Quem usa
  `observeAll()` para sincronizar estado nunca sabe de deleções. Existe `onBeforeDelete` mas não
  `onAfterDelete`.
- `observe(id, ...)` acumula listeners num `ConcurrentHashMap` que nunca é limpo, e não há
  `unobserve`. Vazamento de memória em servidor de longa duração (um listener por jogador que entra).
- `syncId()` e `Caskara.getId()` usam `getDeclaredFields()` — **ignoram campos herdados**. Uma
  entidade que estende uma base com o `@Id` não funciona.
- `Query` não tem `count()`, `exists()`, `delete()`, `fieldNotEquals()` nem `fieldGreaterOrEqual()`.
  `Core.count()` foi adicionado; o equivalente no builder ainda falta.
- `Shell.getFile()` e `Shell.getShellFile()` são idênticos — vale depreciar um.
- `Pearl.sync()` tem timeout fixo de 5s não configurável. Numa VACUUM ou backup demorado, leituras
  legítimas passam a lançar exceção.
- `Caskara.stats()` só devolve o shell padrão, apesar de `CaskaraAdminLogic` já saber agregar todos.

**Dados**

- `exportToJson`/`importFromJson` só carregam `id`, `type` e `json` — **perdem `expires_at`,
  `deleted_at` e `version`**. Um export/import descarta TTLs e ressuscita soft-deletes. O export
  também não descriptografa, e o import faz `GSON.fromJson(content, List.class)` com cast não
  verificado para `Map<String,String>` (o Gson devolve `Map<String,Object>`).
- Backups nunca são rotacionados. Já existem 11 arquivos `.bak` em `test_admin_logic_db/global/backups/`
  no repositório. Com auto-backup de 1h por padrão, isso são 24 arquivos/dia por shell, para sempre.
- `BackupManager` interpola o caminho em `"backup to '" + path + "'"` — quebra com apóstrofo no caminho.
- `dumpEntity` joga o JSON cru no console do servidor, sem descriptografar e sem redigir nada.
- `getGlobalStatsMap()` rotula tamanho de arquivo em disco como `"Memory"`, e a UI mostra "MB" de RAM.

**Repositório / build**

- Estão versionados: `bin/`, `build/`, `test_admin_logic_db/` (banco de teste + 11 backups),
  `libs/HytaleServer.jar`, e arquivos soltos na raiz — `test.java`, `BackupTest.java`,
  `IndexTest.java`, `IndexTestFile.java`. O `.gitignore` cobre `build`, `.gradle`, `/bin`, mas os
  arquivos já rastreados continuam. Sugiro `git rm -r --cached` neles.
- `local.properties` está commitado com caminhos da sua máquina (`/home/cookie/...`).
- `tasks.shadowJar.finalizedBy('deploy')`: **todo build copia o jar para a instalação local do
  Hytale**. Isso quebra CI e qualquer outro contribuidor. Deve ser opt-in
  (`if (project.hasProperty('deploy'))`).
- `entities/User.java`, `entities/FruitBasket.java` e `entities/PlayerStats.java` são entidades de
  exemplo no sourceset **main** — vão parar no jar publicado. Melhor mover para `src/test` ou para um
  módulo de exemplos.

**Testes** — lacunas que deixariam os bugs #1, #2, #6, #9 e #10 passarem de novo:

- leitura dentro de transação (`tx.load`)
- transação aninhada
- `save(obj, ttlMillis)` verificando que o registro ainda existe logo depois
- `@TTL` sem argumentos
- `query().fetch()` conferindo que o campo `@Id` veio preenchido
- filtro `field(nome, <número>)` em entidade `@Encrypted`
- migração de schema sobre entidade `@Encrypted`
- colisão de id entre dois tipos no mesmo shell (item #4)

---

## Arquivos alterados

| Arquivo | Itens |
|---|---|
| `db/Core.java` | 2, 3, 6, 15, 16, 19 (`count()`), 9 (`materialize()`) |
| `db/Shell.java` | 7, 8, 11, 12, 13, 14 |
| `db/Query.java` | 9, 10, 24, 25 |
| `Caskara.java` | 1, 19, 20, 21 |
| `commands/CaskaraAdminLogic.java` | 14, 23 |
| `ui/CaskaraAdminPage.java` | 23 |
| `MainPlugin.java` | 22 |
| `utils/CaskaraLogger.java` | 26 |
| `README.md`, `DOCS.md`, `CURSEFORGE.md` | 17 |
