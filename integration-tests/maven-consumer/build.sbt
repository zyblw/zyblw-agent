import sbt.*
import sbt.Keys.*

ThisBuild / scalaVersion := "3.8.4"

lazy val agentVersion = sys.env
  .get("ZYBLW_AGENT_VERSION")
  .map(_.trim)
  .filter(_.nonEmpty)
  .getOrElse(sys.error("ZYBLW_AGENT_VERSION must name an exact Maven-local or Central version"))

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"             % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers"        % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-rag"              % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-document-loaders" % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-rerank"           % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-postgres"         % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-zio-http"         % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-mcp"              % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-opentelemetry"    % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-evals"            % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-testkit"          % agentVersion % Test
)
