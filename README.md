# 🐚 Caskara - Banco de Dados Simples para Hytale

**Caskara** é uma API de banco de dados versátil e fácil de usar, feita para que você possa focar no seu mod e não em como salvar os dados.

## 🚀 Como começar (O jeito fácil)

### 1. Inicializar
No seu `setup()` do plugin:
```java
Caskara.init(new File("mods/Caskara/data"));
```

### 2. Guia Completo de CRUD (O Básico)

Aqui estão exemplos prontos para você gerenciar seus dados:

```java
// --- CREATE / UPDATE (Criar ou Atualizar) ---
User novo = new User("Cookie", 25);
Caskara.save("user_1", novo); 

// ⚠️ IMPORTANTE: O ID funciona como uma Chave Primária.
// Se você salvar outro objeto com o MESMO ID, ele SOBRESCREVE o anterior.
User novo2 = new User("Cookie2", 20);
Caskara.save("user_1", novo2); // Agora o registro "user_1" contém o Cookie2.

// --- READ (Ler) ---
User carregado = Caskara.load("user_1", User.class);
if (carregado != null) {
    System.out.println("Nome: " + carregado.getName());
}

// --- DELETE (Deletar) ---
Caskara.delete("user_1", User.class);

// --- LIST (Listar todos) ---
List<User> todos = Caskara.list(User.class);
```

---

## � Como trabalhar com Listas?

Existem duas formas principais de lidar com listas no Caskara, dependendo do que você precisa:

### 1. Registros Individuais (O jeito padrão)
Se você quer uma lista de "todos os usuários do servidor", salve cada um com um **ID único**. Depois, use o `list()` para puxar todos.

```java
// Registrar usuários com IDs diferentes
Caskara.save("player_uuid_1", user1);
Caskara.save("player_uuid_2", user2);

// Puxar a lista completa depois
List<User> todosOsPlayers = Caskara.list(User.class);
```

### 2. Lista dentro de um Objeto (Documento)
Se você quer uma lista específica (ex: "Membros de uma Guilda" ou "Itens de uma Mochila"), crie uma classe que **contém** uma lista.

```java
public class Guild {
    private String name;
    private List<String> memberUuids = new ArrayList<>();
    
    public void addMember(String uuid) { memberUuids.add(uuid); }
}

// Salva a guilda inteira (com a lista dentro)
Caskara.save("guild_id_abc", minhaGuilda);
```

---

## �💡 Esqueça as Colunas: Sua Classe é o Esquema

No Caskara, você **não cria tabelas ou colunas** manualmente. A estrutura do banco de dados é definida automaticamente pelas variáveis da sua classe Java.

### Exemplo: Como criar uma "Lista de Frutas"?

Em bancos tradicionais, você precisaria de uma tabela `fruits` com colunas `name`, `color`, etc. No Caskara, você apenas faz isso:

```java
// 1. Defina sua classe e seus campos (Suas "Colunas")
public class FruitBasket {
    private String name;
    private List<Fruit> fruits; // Uma lista dentro do objeto!
}

public class Fruit {
    public String name;
    public String color;
}

// 2. Salve direto. O Caskara cria a estrutura para você.
Caskara.save("cesta_01", minhaCesta);
```

**Por que isso é bom?**
*   **Rapidez**: Quer adicionar uma "coluna" nova? Basta adicionar um campo `private String preco;` na sua classe Java e pronto!
*   **Flexibilidade**: Você pode ter listas, mapas e objetos complexos dentro de um único registro.

---

## 💎 Funcionalidades e Tipos de Dados

O Caskara suporta dados complexos automaticamente (Listas, Números, Objetos aninhados).

### Exemplo de Dados Diferentes:
```java
public class PlayerStats {
    private String name;            // Texto
    private int level;              // Número
    private List<String> inventory; // LISTAS (Trabalha direto com List, Set, Map)
    private HomeLocation home;      // OBJETOS ANINHADOS
}

// Salvar é igual:
Caskara.save("player_1", stats);
```

### 🧬 Sistema de Versões (Flexibilidade)
Se você atualizar seu código e adicionar um novo campo (ex: `String email`), o Caskara não vai quebrar.
- Ao carregar dados antigos que não tinham email, o Java vai receber `null`.
- Sem erros, sem necessidade de deletar o banco antigo!

---

## 🔍 Busca Avançada (Query)

Se quiser buscar por campos específicos dentro do banco:

```java
List<User> resultado = Caskara.core(User.class).query()
    .field("name", "Cookie") // Busca onde o nome é Cookie
    .fetch();
```

---

## 🌍 Escopos (Mundial vs Global)

Por padrão, o Caskara salva tudo na pasta `global`. Se quiser salvar algo específico de um mundo:

```java
// Algo que só existe nesse mundo
var mundoShell = Caskara.shell(world, "database_do_mundo");
mundoShell.save("id", objeto);
```

---

## 📁 Onde ficam os arquivos?
O Caskara organiza tudo para você:
- `global/default.db`: Onde as coisas do `Caskara.save()` ficam.
- `worlds/[mundo]/[nome].db`: Bancos específicos de cada mundo.

## 📄 Licença
Distribuído sob a licença MIT.
