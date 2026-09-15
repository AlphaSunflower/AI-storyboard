export function partitionImageQueueTasks<T extends { mode?: string | null }>(tasks: T[]): {
  auto: T[];
  refine: T[];
} {
  return {
    auto: tasks.filter((task) => task.mode === 'generate'),
    refine: tasks.filter((task) => task.mode !== 'generate'),
  };
}
