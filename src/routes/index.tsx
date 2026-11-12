import { createFileRoute } from "@tanstack/react-router";
import { ArcanumApp } from "@/components/arcanum-app";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <ArcanumApp />;
}
