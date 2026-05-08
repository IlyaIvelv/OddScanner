import { pgTable, serial, text, boolean, integer, timestamp } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";

export const schedulerConfigTable = pgTable("scheduler_config", {
  id: serial("id").primaryKey(),
  cronExpression: text("cron_expression").notNull().default("*/5 * * * *"),
  isEnabled: boolean("is_enabled").notNull().default(true),
  minProfitPct: integer("min_profit_pct").notNull().default(0),
  lastRunAt: timestamp("last_run_at"),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
});

export const insertSchedulerConfigSchema = createInsertSchema(schedulerConfigTable).omit({ id: true, updatedAt: true });
export type InsertSchedulerConfig = z.infer<typeof insertSchedulerConfigSchema>;
export type SchedulerConfig = typeof schedulerConfigTable.$inferSelect;
