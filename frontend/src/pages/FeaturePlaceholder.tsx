import { Card, Result, Typography } from 'antd'

export function FeaturePlaceholder({ title, description }: { title: string; description: string }) {
  return <Card><Result status="info" title={title} subTitle={<Typography.Text type="secondary">{description}</Typography.Text>} /></Card>
}
