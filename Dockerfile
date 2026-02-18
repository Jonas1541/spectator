# --- Stage 1: Build (Compilação) ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copia apenas os arquivos de dependência primeiro (Cache Layering)
COPY gradle/ gradle/
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Baixa as dependências (sem compilar o código ainda)
# Isso faz com que builds futuros sejam muito rápidos se você não mexer no gradle
RUN ./gradlew dependencies --no-daemon

# Copia o código fonte e faz o build final
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

# --- Stage 2: Runtime (Execução Leve) ---
FROM eclipse-temurin:25-jre
WORKDIR /app

# Cria um usuário não-root por segurança (Best Practice)
RUN groupadd -r spectator && useradd -r -g spectator spectator
USER spectator

# Copia o JAR gerado no estágio anterior
COPY --from=build /app/build/libs/spectator-*.jar app.jar

# Configurações de execução
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]