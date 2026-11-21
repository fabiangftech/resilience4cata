# Releasing

Como o resilience4cata é publicado no Maven Central. Tudo usa os plugins próprios do Gradle `maven-publish` e `signing`; não há plugin de publicação de terceiros.

## O que é publicado :id=what-is-published

| artifactId | Conteúdo |
|---|---|
| `resilience4cata-annotations` | jar, fontes, Javadoc, POM, metadados de módulo do Gradle |
| `resilience4cata-core` | o mesmo; depende de `org.jspecify:jspecify` (escopo `compile`) |
| `resilience4cata-processor` | o mesmo; depende de `resilience4cata-annotations` (escopo `runtime`, para o processor path) |
| `resilience4cata-config` | o mesmo; depende de `resilience4cata-core` (`compile`) |
| `resilience4cata-micrometer` | o mesmo; depende de `resilience4cata-core` e `io.micrometer:micrometer-core` (`compile`) |
| `resilience4cata-testkit` | o mesmo; depende de `resilience4cata-core` (`compile`) |
| `resilience4cata-opentelemetry` | o mesmo; depende de `resilience4cata-core` e `io.opentelemetry:opentelemetry-api` (`compile`, a versão mais antiga suportada, 1.62.0) |
| `resilience4cata-reactor` | o mesmo; depende de `resilience4cata-core` e `io.projectreactor:reactor-core` (`compile`, a versão mais antiga suportada, 3.6.0) |
| `resilience4cata-junit` | o mesmo; depende de `resilience4cata-testkit` e `org.junit.jupiter:junit-jupiter-api` 5.10.5 (`compile`) |
| `resilience4cata-all` | jar (só o `module-info`), fontes, um jar Javadoc com um README (o javadoc recusa um módulo sem pacotes), POM, metadados de módulo; depende de `core`, `annotations` e `config` (`compile`) |
| `resilience4cata-bom` | só POM e metadados de módulo: restrições sobre todos os módulos acima, na mesma versão |

groupId `dev.resilience4cata`. Todo jar traz `LICENSE` e `NOTICE` (Apache-2.0) em `META-INF`, e os arquivos são reproduzíveis (sem timestamps de arquivo, ordem fixa). `examples`, `benchmark` e `stress` não são publicados.

## Como o build está organizado :id=how-the-build-is-organized

- **`r4c.publish`** (`conventions/.../PublishConventionPlugin.java`), aplicado por cada módulo publicado: uma publicação `mavenJava` com os campos de POM que o Central exige (nome, descrição a partir da `description` do módulo, url, licença, desenvolvedor, scm, issue tracker), assinatura, e um repositório de arquivos `centralStaging` em `build/central-staging`.
- **`r4c.central`** (`CentralConventionPlugin.java`), aplicado pelo `build.gradle` raiz:
  - `centralBundle` apaga `build/central-staging`, publica todos os módulos ali, verifica o resultado e o compacta em `build/central-bundle.zip`. A verificação falha com uma versão `-SNAPSHOT`, um módulo faltando, um jar de fontes ou Javadoc faltando, ou qualquer arquivo sem sua assinatura `.asc` e seus checksums `.md5`/`.sha1`. Só entram os arquivos da versão da release (sem `maven-metadata.xml`), e sem os checksums `.sha256`/`.sha512` que o Gradle também escreve: o Central não os exige, e cada arquivo do bundle conta para a cota mensal de arquivos do Central Portal (1.000 arquivos no plano gratuito; uma release de todos os módulos tem cerca de 250 arquivos).
  - `centralUpload` envia o zip para a Publisher API do Central Portal (`POST /api/v1/publisher/upload`, `publishingType=USER_MANAGED`). Nada depende dela, então o `build` nunca faz upload, e ela falha sem token. Um deployment `USER_MANAGED` é validado pelo Central e depois espera no portal até alguém apertar **Publish**.
- **`apiCompatibility`** (`ApiCompatibilityTask.java`, adicionada pelo `r4c.publish` e executada pelo `check`, portanto pelo `build` e pela CI): o japicmp compara o jar de cada módulo publicado com o mesmo artefato em `r4c.apiBaseline` (em `gradle.properties`: a última release) do Maven Central, membros public e protected, compatibilidade binária e de código-fonte. A API são os pacotes que o módulo exporta em `module-info.java`, ou `r4cApi.packages` no seu arquivo de build para um módulo sem ele (`micronaut`, `quarkus:runtime`); um módulo que não exporta nenhum (`processor`, `quarkus:deployment`) é ignorado, assim como um módulo sem baseline (novo desde a última release, ou offline). Classes e membros anotados com `@Internal` (públicos só para outro módulo deste projeto ou para o `ServiceLoader`) ou `@Experimental` (ainda não estabilizados) ficam de fora. Contra uma baseline anterior a essas anotações (0.11.0) eles aparecem como removidos; a partir de uma baseline que as tem, ficam de fora dos dois lados. O relatório é `<module>/build/reports/api-compatibility/api-changes.md`. Antes da 1.0, uma mudança incompatível é um aviso; a partir da 1.0, ela falha o build a menos que a versão major mude. O japicmp roda pela Worker API do Gradle, então não adiciona nenhum plugin do Gradle.
- **`consumer/check.sh`** (workflow `consumer`, em PRs e pushes para `main` que mexem em arquivos de build, `module-info.java`, recursos ou no processor): instala o resilience4cata como um usuário faz, a partir dos arquivos publicados, que os próprios testes do build nunca leem (eles usam dependências de projeto). Ele publica todos os módulos em `build/central-staging` (`--staged` verifica o que já está lá), verifica que o BOM lista exatamente os artefatos publicados, e então constrói e executa uma pequena aplicação (`consumer/app`: uma interface e uma classe anotadas, um método assíncrono, `resilience4cata.properties`) com `-Xlint:all -Werror`, com Gradle no class path e como módulo nomeado (`consumer/gradle`) e com Maven no class path (`consumer/maven`, repositório local próprio em `build/consumer/m2`), e resolve todos os artefatos do BOM com suas dependências com as duas ferramentas. Verificado por mutação: `config` como dependência `implementation` de `all`, o `module-info` de `all` sem `config`, um módulo deixado fora do BOM.
- `opentelemetry` informa sua versão como versão do instrumentation scope do OpenTelemetry: `processResources` escreve a `version` do build em `dev/resilience4cata/opentelemetry/version.properties`, então uma release (inclusive com `-Pversion=…`) não precisa de passo manual.
- A assinatura é pulada quando nenhuma chave está definida, então `./gradlew build` e `./gradlew publishToMavenLocal` não precisam de segredos.

Propriedades do Gradle (ou variáveis de ambiente `ORG_GRADLE_PROJECT_<name>`):

| Propriedade | Usada por | Significado |
|---|---|---|
| `signingInMemoryKey` | assinatura | Chave secreta em ASCII armor (`gpg --armor --export-secret-keys <id>`) |
| `signingInMemoryKeyPassword` | assinatura | Sua frase secreta |
| `signingInMemoryKeyId` | assinatura | Opcional: o id de uma subchave de assinatura |
| `centralPortalUsername`, `centralPortalPassword` | `centralUpload` | User token do Central Portal |
| `r4c.scmRepository` | POM | `owner/repository` no GitHub, em `gradle.properties` |
| `r4c.developerId`, `r4c.developerName` | POM | Entrada de desenvolvedor, em `gradle.properties` |

Guarde os segredos em `~/.gradle/gradle.properties` ou no ambiente, nunca no repositório. Com o configuration cache ativado, o Gradle guarda os valores das propriedades no seu cache (criptografado) em `.gradle/`; passe `--no-configuration-cache` ao assinar numa máquina compartilhada.

## Configuração única :id=one-time-setup

1. Crie uma conta em [central.sonatype.com](https://central.sonatype.com).
2. Verifique o namespace `dev.resilience4cata`: no portal, **Namespaces → Add namespace**, depois adicione a chave de verificação que ele mostra como registro DNS TXT em `resilience4cata.dev` e aperte **Verify**.
3. Gere um user token (**View Account → Generate User Token**). Suas duas partes são `centralPortalUsername` e `centralPortalPassword`.
4. Crie uma chave de assinatura e publique a parte pública num keyserver que o Central consulte (por exemplo `keys.openpgp.org` ou `keyserver.ubuntu.com`):

   ```bash
   gpg --quick-gen-key "resilience4cata releases <you@example.com>" rsa4096 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # value of signingInMemoryKey
   ```

5. Para o workflow do GitHub, crie o environment `production` (Settings → Environments; o job de release o declara) e adicione a ele os segredos `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` e, para fazer upload pela CI, `CENTRAL_PORTAL_USERNAME` e `CENTRAL_PORTAL_PASSWORD`. Restrinja o environment a tags `v*` para que só uma tag de release possa lê-los.
6. Defina `r4c.scmRepository` (e as propriedades de desenvolvedor) em `gradle.properties` com o repositório real do GitHub.

## Passos da release :id=release-steps

1. Verifique as mudanças de API desde a última release: `./gradlew apiCompatibility`, depois leia os avisos e `*/build/reports/api-compatibility/api-changes.md`; toda mudança incompatível vai para o changelog. Verifique também a regra do código gerado (o código gerado pelo processor `1.a` roda no core `1.b` para todo `b >= a`): `./gradlew :examples:check -Pr4c.generatedBy=<last release>` compila os testes de `examples`, e o código gerado a partir deles, contra o processor, as anotações e os módulos da última release vindos do Maven Central, e depois os executa sobre os módulos deste build. Um método que o código gerado chama e que mudou de forma binariamente incompatível falha ali com um `NoSuchMethodError`, mesmo quando seu código-fonte ainda compila. A CI executa isso a cada mudança contra `r4c.apiBaseline` (`-Pr4c.generatedBy=baseline`, o job `generated-code` de `build.yml`), então este passo só lê o resultado. Os fontes de teste precisam compilar contra essa release: um teste que usa API adicionada depois vai para `examples/generated-code-exclusions.txt`. As outras promessas da [página de compatibilidade](reference/compatibility.md#how-it-is-checked) são verificadas pelos `ContractTest`s contra os arquivos de `contract/`: toda linha removida ali desde a última release é uma mudança incompatível.
2. Defina a versão da release em `gradle.properties`: `version=0.1.0`. Faça o commit (`release 0.1.0`) e a tag: `git tag v0.1.0`, depois envie o commit e a tag. A `main` só aceita mudanças por pull requests cujas verificações passaram (`build` em JDK 21 e 25, `generated code (last release)`, `alloc-gate`, `sonar`; o ruleset `default`, que também proíbe force pushes e apagar o branch); os administradores do repositório o contornam, e é isso que permite enviar diretamente os commits de release dos passos 2 e 6.
3. Construa o bundle, de uma destas formas:
   - na CI: execute o workflow **release** (`.github/workflows/release.yml`) na tag `v0.1.0`. Ele primeiro executa o build inteiro em JDK 21 e 25 (o mesmo workflow `build` da CI, no commit da tag), e só então constrói, assina e verifica o bundle, executa `consumer/check.sh --staged` sobre seus arquivos, arquiva `central-bundle.zip` como artefato do workflow, e faz o upload se os segredos do token estiverem definidos; ou
   - localmente: `./gradlew build centralBundle --no-configuration-cache` com as propriedades de assinatura definidas, depois `consumer/check.sh --staged`.
4. Faça o upload, se a CI não fez: `./gradlew centralUpload -x centralBundle`, ou no portal **Publish Component**, escolha `build/central-bundle.zip`.
5. No portal, abra **Deployments**, espere por *Validated*, revise os arquivos e aperte **Publish** (ou **Drop** para descartá-lo). Os artefatos chegam ao Maven Central em cerca de 30 minutos.
6. Suba para a próxima versão de desenvolvimento: `version=0.2.0-SNAPSHOT`, commit.
7. Assim que a release estiver visível no Maven Central, atualize a versão escrita na documentação e no `README.md` da raiz (snippets de build no site, páginas iniciais, changelog): `grep -rn "0\.1\.0" README.md docs/en docs/es`. Defina também `r4c.apiBaseline=0.1.0` em `gradle.properties`, para que `apiCompatibility` e a verificação do código gerado comparem com a nova release, e esvazie `examples/generated-code-exclusions.txt`, cujos testes agora compilam contra ela. Adicione `config/src/test/resources/upgrade/0.1.0.properties`, um arquivo de configuração que usa todas as chaves da nova release (copie o anterior e acrescente o que é novo); `UpgradeTest` falha sem ele, verifica que a release do Maven Central o lê e, daí em diante, que toda versão posterior o lê da mesma forma. Nunca edite um existente para fazer o teste passar.
8. Crie a release do GitHub a partir da tag, com a seção do changelog da versão como notas (links relativos convertidos em absolutos: `errors.md#r4c-apt-007` → `https://resilience4cata.dev/en/errors?id=r4c-apt-007`) e uma linha de instalação: `gh release create v0.1.0 --verify-tag --title 0.1.0 --notes-file notes.md`. Uma release candidate recebe `--prerelease --latest=false`; assim que os snippets de instalação a mostrarem (passo 7), marque-a como *Latest* com `gh release edit v1.0.0-RC3 --prerelease=false --latest` (o *Latest* do GitHub não pode ser uma prerelease), para que a página do repositório mostre a versão que o site instala.

Para verificar um bundle sem publicar nada, construa-o com uma chave descartável e uma versão de release:

```bash
gpg --quick-gen-key "throwaway <throwaway@example.invalid>" rsa3072 sign 1d
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys throwaway@example.invalid)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase> \
./gradlew centralBundle -Pversion=0.1.0 --no-configuration-cache
unzip -l build/central-bundle.zip
```
